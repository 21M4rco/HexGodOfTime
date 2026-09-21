"""Boot the Forge dedicated server and verify all Warping datapacks load.

An isolated disposable world is used. Nothing connects to a public server.
"""
import os
from pathlib import Path
import re
import queue
import threading
import signal
import subprocess
import sys
import time

run = Path('run')
run.mkdir(exist_ok=True)
(run / 'eula.txt').write_text('eula=true\n')
(run / 'server.properties').write_text('level-name=warping-smoke\nonline-mode=false\nserver-port=0\nview-distance=2\nsimulation-distance=2\n')
proc = subprocess.Popen(['gradle', '--no-daemon', '-PwarpingSmoke', 'runServer'], stdout=subprocess.PIPE,
                        stderr=subprocess.STDOUT, text=True, start_new_session=True)
lines = queue.Queue()
def read_output():
    for line in proc.stdout:
        lines.put(line)
threading.Thread(target=read_output, daemon=True).start()
end = time.monotonic() + 240
ready = False
regressions = False
pilgrim = False
with open('warping-server-smoke.log', 'w') as log:
    try:
        while time.monotonic() < end and proc.poll() is None:
            try:
                line = lines.get(timeout=1)
            except queue.Empty:
                continue
            log.write(line)
            log.flush()
            print(line, end='', flush=True)
            if re.search(r'Done \([\d.]+s\)!', line):
                ready = True
            if 'WARPING_SERVER_REGRESSIONS_PASSED' in line:
                regressions = True
            if 'PILGRIM_SERVER_REGRESSIONS_PASSED' in line:
                pilgrim = True
            if ready and regressions and pilgrim:
                break
    finally:
        if proc.poll() is None:
            os.killpg(proc.pid, signal.SIGTERM)
            try:
                proc.wait(timeout=20)
            except subprocess.TimeoutExpired:
                os.killpg(proc.pid, signal.SIGKILL)
                proc.wait()
if not ready or not regressions or not pilgrim:
    sys.exit('Dedicated server startup or Warping regression checks failed; see warping-server-smoke.log')
print('Dedicated-server startup, datapack load, solar damage and kill regression checks passed.')
