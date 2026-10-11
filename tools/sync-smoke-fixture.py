"""Isolated Skyrim shared-memory stand-in, including native-style position feedback.

Uses its own mapping; never opens the installed Skyrim mapping or saves.
"""
import json
import math
import os
from pathlib import Path
import struct
import sys
import time

os.environ['SKYCRAFT_LINK'] = r'Local\SkyCraft_world_smoke'
import fake_skyrim as fs

def main():
    output = Path(sys.argv[1])
    link = fs.Link()
    initial = (fs.X0 + .5, fs.FLOOR_Y, .5)
    position = initial
    seq, epoch = 1234567, 9876
    samples, maximum, accepted = [], 0.0, 0
    sent = False
    start = time.monotonic()
    try:
        while time.monotonic() - start < 240:
            link.heartbeat()
            if link.mc_alive() and not sent:
                link.write_col(1, struct.pack('<I', epoch))
                for rx in range(fs.X0//8-2, fs.X0//8+2):
                    for rz in range(-2, 2):
                        for ry in range(11, 15):
                            link.write_col(3, fs.floor_tris_payload(rx, ry, rz, epoch))
                            link.write_col(2, fs.region_payload(rx, ry, rz, epoch, fs.world))
                sent = True
            # Seqlock: reject torn observations just as the native reader does.
            first = struct.unpack_from('<I', link.m, fs.OFF_MC)[0]
            state = link.read_mc()
            second = struct.unpack_from('<I', link.m, fs.OFF_MC)[0]
            if first == second and not first & 1 and state['flags'] & 1 and state['ack'] == seq:
                position = state['pos']
                accepted += 1
                drift = math.dist(initial, position)
                maximum = max(maximum, drift)
                if not math.isfinite(drift) or drift > .5:
                    samples.append({'elapsed': time.monotonic()-start, 'position': position, 'ack': state['ack'], 'flags': state['flags']})
                    raise AssertionError(f'Unsafe position drove stand-in Skyrim: {position}, drift={drift}')
            link.write_sky(1, position, -90, 10, seq, epoch)
            if accepted and not link.mc_alive():
                break
            time.sleep(.002)
        result = {'result': 'PASS' if accepted else 'FAIL', 'acceptedFrames': accepted, 'maximumDriftBlocks': maximum, 'samples': samples}
    except Exception as error:
        result = {'result': 'FAIL', 'acceptedFrames': accepted, 'maximumDriftBlocks': maximum, 'samples': samples, 'error': str(error)}
    output.write_text(json.dumps(result, indent=2), encoding='utf-8')
    link.m.close()
    return 0 if result['result'] == 'PASS' else 1

if __name__ == '__main__':
    raise SystemExit(main())
