"""Boot the Forge dedicated server and verify all Warping datapacks load.

An isolated disposable world is used. Nothing connects to a public server.
"""
import os
from pathlib import Path
import re
import selectors
import signal
import subprocess
import sys
import time

run = Path('run')
run.mkdir(exist_ok=True)
(run / 'eula.txt').write_text('eula=true\n')
(run / 'server.properties').write_text('level-name=warping-smoke\nonline-mode=false\nserver-port=0\nview-distance=2\nsimulation-distance=2\n')
proc = subprocess.Popen(['gradle', '--no-daemon', 'runServer'], stdout=subprocess.PIPE,
                        stderr=subprocess.STDOUT, text=True, start_new_session=True)
selector = selectors.DefaultSelector()
selector.register(proc.stdout, selectors.EVENT_READ)
end = time.monotonic() + 240
ready = False
with open('warping-server-smoke.log', 'w') as log:
    try:
        while time.monotonic() < end and proc.poll() is None:
            for key, _ in selector.select(timeout=1):
                line = key.fileobj.readline()
                log.write(line)
                log.flush()
                print(line, end='', flush=True)
                if re.search(r'Done \([\d.]+s\)!', line):
                    ready = True
                    break
            if ready:
                break
    finally:
        if proc.poll() is None:
            os.killpg(proc.pid, signal.SIGTERM)
            try:
                proc.wait(timeout=20)
            except subprocess.TimeoutExpired:
                os.killpg(proc.pid, signal.SIGKILL)
                proc.wait()
if not ready:
    sys.exit('Dedicated server did not reach a ready state; see warping-server-smoke.log')
print('Dedicated-server startup and datapack load passed.')
