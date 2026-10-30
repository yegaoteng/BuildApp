const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('api', {
  selectDirectory: () => ipcRenderer.invoke('select-directory'),
  getWorkingDir: () => ipcRenderer.invoke('get-working-dir'),
  startGradleInstall: () => ipcRenderer.invoke('start-gradle-install'),
  startBuild: (opts) => ipcRenderer.invoke('start-build', opts),
  stopProcess: () => ipcRenderer.invoke('stop-process'),
  isProcessRunning: () => ipcRenderer.invoke('is-process-running'),
  resizeTerminal: (cols, rows) => ipcRenderer.invoke('resize-terminal', { cols, rows }),
  checkGradle: () => ipcRenderer.invoke('check-gradle'),

  onPtyData: (cb) => ipcRenderer.on('pty-data', (_, data) => cb(data)),
  onPtyExit: (cb) => ipcRenderer.on('pty-exit', (_, payload) => cb(payload)),
});
