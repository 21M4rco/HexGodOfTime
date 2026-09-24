"""Boot the development client far enough to apply client-only frost renderer mixins."""
import os
from pathlib import Path
import queue
import re
import signal
import subprocess
import sys
import threading
import time

environment=os.environ.copy()
environment['ALSOFT_DRIVERS']='null'  # CI has no physical audio device.
proc=subprocess.Popen(['xvfb-run','-a','gradle','--no-daemon','runClient'],stdout=subprocess.PIPE,
                      stderr=subprocess.STDOUT,text=True,start_new_session=True,env=environment)
lines=queue.Queue()
def read_output():
    for line in proc.stdout:
        lines.put(line)
threading.Thread(target=read_output,daemon=True).start()
deadline=time.monotonic()+240
ready_at=None
failed=False
with Path('frost-client-smoke.log').open('w') as log:
    try:
        while time.monotonic()<deadline and proc.poll() is None:
            if ready_at is not None and time.monotonic()-ready_at>=12:break
            try: line=lines.get(timeout=1)
            except queue.Empty: continue
            log.write(line);log.flush();print(line,end='',flush=True)
            if re.search(r'MixinApplyError|Critical injection failure|Exception caught during firing event|Failed to create window',line):
                failed=True;break
            if 'Reloading ResourceManager' in line:ready_at=time.monotonic()
    finally:
        if proc.poll() is None:
            os.killpg(proc.pid,signal.SIGTERM)
            try:proc.wait(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(proc.pid,signal.SIGKILL);proc.wait()
if failed or ready_at is None:sys.exit('Client did not complete renderer startup; see frost-client-smoke.log')
print('Client renderer mixins loaded; startup reached resource reload without an injection failure.')
