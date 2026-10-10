"""Read only item identifiers from locally owned Bethesda plugins; never copy game assets."""
import json, struct, sys, zlib
from pathlib import Path

def records(path):
    data = path.read_bytes()
    def walk(start, end):
        pos = start
        while pos + 24 <= end:
            kind = data[pos:pos+4].decode('ascii')
            size = struct.unpack_from('<I', data, pos+4)[0]
            if kind == 'GRUP':
                yield from walk(pos+24, pos+size)
                pos += size
                continue
            flags, form = struct.unpack_from('<II', data, pos+8)
            body = data[pos+24:pos+24+size]
            pos += 24+size
            if kind not in ('MISC','WEAP','ARMO','AMMO','ALCH','INGR','BOOK','SLGM','LIGH','KEYM'): continue
            if flags & 0x40000: body = zlib.decompress(body[4:])
            sub, off, extended = {}, 0, None
            while off+6 <= len(body):
                tag=body[off:off+4].decode('ascii'); length=struct.unpack_from('<H',body,off+4)[0]; off+=6
                if tag=='XXXX': extended=struct.unpack_from('<I',body,off)[0];off+=length;continue
                length=extended if extended is not None else length; extended=None
                sub.setdefault(tag,[]).append(body[off:off+length]);off+=length
            if 'EDID' not in sub:continue
            edid=sub['EDID'][0].rstrip(b'\0').decode('utf-8')
            # Only records originally defined by this file, not masters' overrides.
            masters=len(sub.get('MAST',[])) # masters actually come from TES4, see below
            yield dict(type=kind,form=form,editor=edid,ench='EITM' in sub,effects=[struct.unpack('<I',b)[0] for b in sub.get('EFID',[])])
    # TES4 is uncompressed. Count its MAST subrecords to determine the file-local index.
    size=struct.unpack_from('<I',data,4)[0];body=data[24:24+size];off=0;masters=0
    while off+6<=len(body):
        tag=body[off:off+4];length=struct.unpack_from('<H',body,off+4)[0];off+=6
        if tag==b'MAST':masters+=1
        off+=length
    for r in walk(24+size,len(data)):
        if r['form']>>24==masters:
            r['form']=f"{r['form'] & 0xffffff:06X}";r['plugin']=path.name;yield r

if __name__=='__main__':
    out=[]
    for arg in sys.argv[1:]:out.extend(records(Path(arg)))
    print(json.dumps(out,ensure_ascii=True,indent=2))
