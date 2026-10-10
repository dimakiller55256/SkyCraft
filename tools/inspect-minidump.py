"""Read exception/module metadata from a Windows minidump, without printing memory contents."""
import argparse
import json
import mmap
from pathlib import Path
import struct


def inspect(path):
    with path.open('rb') as stream, mmap.mmap(stream.fileno(), 0, access=mmap.ACCESS_READ) as data:
        if data[:4] != b'MDMP':
            raise ValueError('Not a Windows minidump')
        count, directory = struct.unpack_from('<II', data, 8)
        entries = {}
        for index in range(count):
            kind, size, offset = struct.unpack_from('<III', data, directory + index * 12)
            entries[kind] = (offset, size)
        modules = []
        if 4 in entries:
            offset, size = entries[4]
            count = struct.unpack_from('<I', data, offset)[0]
            if 4 + count * 108 > size:
                raise ValueError('Invalid module list')
            for index in range(count):
                module = offset + 4 + index * 108
                base, length, _, _, name_rva = struct.unpack_from('<QIIII', data, module)
                name_size = struct.unpack_from('<I', data, name_rva)[0]
                name = data[name_rva + 4:name_rva + 4 + name_size].decode('utf-16-le')
                modules.append(dict(path=name, base=base, size=length))
        def where(address):
            for module in modules:
                if module['base'] <= address < module['base'] + module['size']:
                    return dict(module=module['path'], offset=hex(address - module['base']), address=hex(address))
            return dict(address=hex(address))
        result = dict(file=str(path), streams=sorted(entries), modules=len(modules))
        if 6 in entries:
            offset, size = entries[6]
            thread = struct.unpack_from('<I', data, offset)[0]
            code, flags, nested, address, number, _ = struct.unpack_from('<IIQQII', data, offset + 8)
            parameters = struct.unpack_from('<15Q', data, offset + 40)[:min(number, 15)]
            result['exception'] = dict(thread=thread, code=f'0x{code:08X}', flags=flags, location=where(address), parameters=list(parameters))
            context_size, context_rva = struct.unpack_from('<II', data, offset + 160)
            if context_size >= 256:
                # AMD64 CONTEXT: RSP 152, RBP 160, RIP 248. Architecture flag checked first.
                flags = struct.unpack_from('<I', data, context_rva + 48)[0]
                if flags & 0x100000:
                    rsp, rbp = struct.unpack_from('<QQ', data, context_rva + 152)
                    rip = struct.unpack_from('<Q', data, context_rva + 248)[0]
                    result['context'] = dict(rsp=hex(rsp), rbp=hex(rbp), rip=where(rip))
                    if 5 in entries:
                        memory_rva, memory_size = entries[5]
                        ranges = struct.unpack_from('<I', data, memory_rva)[0]
                        candidates = []
                        for index in range(ranges):
                            base, length, source = struct.unpack_from('<QII', data, memory_rva + 4 + index * 16)
                            if base <= rsp < base + length:
                                cursor = source + rsp - base
                                for position in range(0, min(8192, base + length - rsp) // 8 * 8, 8):
                                    value = struct.unpack_from('<Q', data, cursor + position)[0]
                                    location = where(value)
                                    if 'module' in location:
                                        candidates.append(dict(stack_offset=hex(position), **location))
                        result['stack_address_candidates'] = candidates[:80]
                        result['stack_note'] = 'Raw stack address candidates; not an unwound call stack. Values may be data rather than return addresses.'
        result['module_paths'] = [module['path'] for module in modules]
        return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('dump', type=Path)
    parser.add_argument('--output', type=Path)
    arguments = parser.parse_args()
    text = json.dumps(inspect(arguments.dump), ensure_ascii=False, indent=2)
    if arguments.output:
        arguments.output.write_text(text + '\n', encoding='utf-8')
    else:
        print(text)
