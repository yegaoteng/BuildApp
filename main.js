const { app, BrowserWindow, dialog, ipcMain, shell } = require('electron');
const path = require('path');
const os = require('os');
const fs = require('fs');
const pty = require('node-pty');

let mainWindow = null;
let ptyProcess = null;
let currentSession = null; // 'install' | 'build'
let workingDir = null;

const HOME = os.homedir();
const GRADLE_INSTALL_DIR = path.join(HOME, '.gradle-install');
const GRADLE_VERSION = '8.14.6';
const PROXY = 'https://gh-proxy.com/';
const GRADLE_DIST_URL = `${PROXY}https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip`;

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1100,
    height: 750,
    minWidth: 900,
    minHeight: 600,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
    title: '应用构建工具',
    backgroundColor: '#1e1e2e',
  });

  mainWindow.loadFile(path.join(__dirname, 'index.html'));
  // mainWindow.webContents.openDevTools();
}

app.whenReady().then(() => {
  createWindow();
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  if (ptyProcess) {
    try { ptyProcess.kill(); } catch (e) {}
  }
  if (process.platform !== 'darwin') app.quit();
});

// ---- IPC handlers ----

ipcMain.handle('select-directory', async () => {
  const result = await dialog.showOpenDialog(mainWindow, {
    properties: ['openDirectory', 'createDirectory'],
    title: '选择工作目录（源码所在文件夹）',
  });
  if (result.canceled || result.filePaths.length === 0) {
    return { canceled: true, path: null };
  }
  workingDir = result.filePaths[0];
  // detect build file
  const hasGradlew = fs.existsSync(path.join(workingDir, 'gradlew'));
  const hasBuildGradle = fs.existsSync(path.join(workingDir, 'build.gradle')) ||
                        fs.existsSync(path.join(workingDir, 'build.gradle.kts'));
  return {
    canceled: false,
    path: workingDir,
    hasGradlew,
    hasBuildGradle,
  };
});

ipcMain.handle('get-working-dir', () => workingDir);

function getGradleHome() {
  return path.join(GRADLE_INSTALL_DIR, `gradle-${GRADLE_VERSION}`);
}

function getGradleBin() {
  return path.join(getGradleHome(), 'bin', 'gradle');
}

function isGradleInstalled() {
  try {
    return fs.existsSync(getGradleBin());
  } catch (e) {
    return false;
  }
}

// Build environment PATH that includes our gradle install
function buildEnv(extraEnv = {}) {
  const env = { ...process.env };
  const gradleHome = getGradleHome();
  if (fs.existsSync(gradleHome)) {
    env.GRADLE_HOME = gradleHome;
    env.PATH = `${path.join(gradleHome, 'bin')}${path.delimiter}${env.PATH || ''}`;
  }
  // Use mirrors for maven central / google
  env.GRADLE_OPTS = `-Dhttps.proxyHost= -Dhttp.proxyHost=`;
  return { ...env, ...extraEnv };
}

// Spawn a pty shell that runs a command and streams output to renderer
function runInPty(command, args, cwd, env, onData, onExit) {
  // We use bash -c so we can run compound commands (download + install)
  const shell = process.platform === 'win32' ? 'powershell.exe' : 'bash';
  const fullCmd = [command, ...args].join(' ');
  ptyProcess = pty.spawn(shell, ['-c', fullCmd], {
    name: 'xterm-256color',
    cols: 100,
    rows: 30,
    cwd: cwd || HOME,
    env: env || process.env,
  });

  ptyProcess.onData((data) => {
    if (onData) onData(data);
  });

  ptyProcess.onExit(({ exitCode, signal }) => {
    if (onExit) onExit(exitCode, signal);
    ptyProcess = null;
  });

  return ptyProcess;
}

// ---- Deploy environment: install Gradle via proxy ----
ipcMain.handle('start-gradle-install', async (event) => {
  if (ptyProcess) {
    return { ok: false, message: '已有进程正在运行' };
  }
  currentSession = 'install';

  // Build the install script
  const gradleHome = getGradleHome();
  const zipPath = path.join(GRADLE_INSTALL_DIR, `gradle-${GRADLE_VERSION}-bin.zip`);
  const installScript = `
set -e
echo "=========================================="
echo "  开始部署 Gradle 环境 (版本 ${GRADLE_VERSION})"
echo "  使用代理: ${PROXY}"
echo "=========================================="
mkdir -p "${GRADLE_INSTALL_DIR}"
cd "${GRADLE_INSTALL_DIR}"
echo "[1/4] 检测现有安装..."
if [ -x "${getGradleBin()}" ]; then
  echo "Gradle 已安装于 ${gradleHome}"
  "${getGradleBin()}" --version
  echo "INSTALL_SUCCESS"
  exit 0
fi
echo "[2/4] 下载 Gradle ${GRADLE_VERSION} (走代理)..."
curl -L --retry 3 --connect-timeout 30 -o "${zipPath}" "${GRADLE_DIST_URL}"
echo "[3/4] 解压..."
unzip -o -q "${zipPath}" -d "${GRADLE_INSTALL_DIR}"
echo "[4/4] 验证..."
"${getGradleBin()}" --version
echo ""
echo "=========================================="
echo "  Gradle 环境部署完成！"
echo "  GRADLE_HOME=${gradleHome}"
echo "=========================================="
echo "INSTALL_SUCCESS"
`;

  const env = buildEnv();
  ptyProcess = pty.spawn('bash', ['-c', installScript], {
    name: 'xterm-256color',
    cols: 120,
    rows: 35,
    cwd: HOME,
    env,
  });

  ptyProcess.onData((data) => {
    event.sender.send('pty-data', data);
  });

  ptyProcess.onExit(({ exitCode }) => {
    event.sender.send('pty-exit', { session: 'install', exitCode });
    currentSession = null;
    ptyProcess = null;
  });

  return { ok: true };
});

// ---- Build app ----
ipcMain.handle('start-build', async (event, { customCommand }) => {
  if (ptyProcess) {
    return { ok: false, message: '已有进程正在运行' };
  }
  if (!workingDir) {
    return { ok: false, message: '请先选择工作目录' };
  }

  currentSession = 'build';
  const gradlewPath = path.join(workingDir, 'gradlew');
  const hasGradlew = fs.existsSync(gradlewPath);

  // Build command
  let buildCmd = '';
  if (customCommand && customCommand.trim()) {
    buildCmd = customCommand.trim();
  } else {
    // Default: use gradlew if exists, else installed gradle
    if (hasGradlew) {
      buildCmd = `./gradlew build --console=plain --no-daemon`;
    } else if (isGradleInstalled()) {
      buildCmd = `"${getGradleBin()}" build --console=plain --no-daemon`;
    } else {
      buildCmd = `gradle build --console=plain --no-daemon`;
    }
  }

  const env = buildEnv();

  ptyProcess = pty.spawn('bash', ['-c', `cd "${workingDir}" && ${buildCmd}`], {
    name: 'xterm-256color',
    cols: 120,
    rows: 35,
    cwd: workingDir,
    env,
  });

  ptyProcess.onData((data) => {
    event.sender.send('pty-data', data);
  });

  ptyProcess.onExit(({ exitCode }) => {
    event.sender.send('pty-exit', { session: 'build', exitCode });
    currentSession = null;
    ptyProcess = null;
  });

  return { ok: true, command: buildCmd, workingDir };
});

ipcMain.handle('stop-process', async () => {
  if (ptyProcess) {
    try {
      ptyProcess.kill();
    } catch (e) {}
    ptyProcess = null;
    currentSession = null;
    return { ok: true };
  }
  return { ok: false, message: '没有正在运行的进程' };
});

ipcMain.handle('is-process-running', () => {
  return { running: !!ptyProcess, session: currentSession };
});

ipcMain.handle('resize-terminal', (event, { cols, rows }) => {
  if (ptyProcess) {
    try { ptyProcess.resize(cols, rows); } catch (e) {}
  }
  return { ok: true };
});

ipcMain.handle('check-gradle', () => {
  return { installed: isGradleInstalled(), path: getGradleHome() };
});
