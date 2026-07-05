const { spawn } = require('child_process');
const sdkmanager = process.env.LOCALAPPDATA + '\\Android\\Sdk\\cmdline-tools\\cmdline-tools\\bin\\sdkmanager.bat';
const child = spawn('cmd', ['/c', sdkmanager, '--licenses', '--sdk_root=' + process.env.LOCALAPPDATA + '\\Android\\Sdk']);

child.stdout.on('data', (data) => {
    console.log('stdout:', data.toString());
});

child.stderr.on('data', (data) => {
    console.log('stderr:', data.toString());
});

let count = 0;
const interval = setInterval(() => {
    child.stdin.write('y\n');
    count++;
    console.log('Sent y, count:', count);
    if (count >= 14) {
        clearInterval(interval);
    }
}, 500);

child.on('close', (code) => {
    console.log('Child process exited with code', code);
});
