"""Release ZIP for the co-op build: the previous friend kit's MO2 and Prism archives with the current
SKSE DLL, Fabric JAR (SkyCraft Link inside), ini and item table swapped in, plus the Russian guides.
Usage: python package-coop-release.py <previous friend kit .zip> <output .zip>"""
import hashlib, io, re, sys, zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAR = re.compile(r'(^|/)skycraft-fabric-[^/]*\.jar$')


def read_zip(data):
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        return {i.filename: z.read(i) for i in z.infolist() if not i.is_dir()}


def write_zip(entries):
    out = io.BytesIO()
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for name, data in sorted(entries.items()):
            z.writestr(zipfile.ZipInfo(name, date_time=(2026, 10, 25, 0, 0, 0)), data, zipfile.ZIP_DEFLATED)
    return out.getvalue()


def swap_jar(entries, jar_name, jar):
    olds = [n for n in entries if JAR.search(n)]
    assert olds, 'no SkyCraft JAR to replace'
    folder = olds[0].rsplit('/', 1)[0] + '/' if '/' in olds[0] else ''
    for n in olds:
        del entries[n]
    entries[folder + jar_name] = jar


def main():
    kit, output = Path(sys.argv[1]), Path(sys.argv[2])
    jars = [p for p in (ROOT / 'fabric/build/libs').glob('skycraft-*.jar') if 'sources' not in p.name]
    jar_path = max(jars, key=lambda p: p.stat().st_mtime)
    jar = jar_path.read_bytes()
    with zipfile.ZipFile(io.BytesIO(jar)) as z:
        assert 'skycraft/SkyCraftLink.exe' in z.namelist(), 'JAR without SkyCraft Link: run BuildLink, then BuildFabric'
    version = re.search(r'skycraft-(.+)\.jar', jar_path.name).group(1)
    jar_name = f'skycraft-fabric-{version}.jar'
    dll = (ROOT / 'skse/build/RelWithDebInfo/SkyCraft.dll').read_bytes()

    old = read_zip(kit.read_bytes())
    mo2_name = next(n for n in old if n.endswith('-MO2.zip'))
    prism_name = next(n for n in old if n.endswith('-Prism-instance.zip'))

    mo2 = read_zip(old[mo2_name])
    mo2['SKSE/Plugins/SkyCraft.dll'] = dll
    mo2['SKSE/Plugins/SkyCraft.ini'] = (ROOT / 'skse/SkyCraft.ini').read_bytes()
    mo2['SKSE/Plugins/SkyCraft-items.json'] = (ROOT / 'config/item-conversion.json').read_bytes()
    bundle_name = 'SKSE/Plugins/SkyCraft/SkyCraft-Minecraft.zip'
    bundle = read_zip(mo2[bundle_name])
    swap_jar(bundle, jar_name, jar)
    bundle['bundle-version.txt'] = f'SkyCraft YS {version} (co-op: STR, map squares, Steam via SkyCraft Link)'.encode()
    mo2[bundle_name] = write_zip(bundle)
    prism = read_zip(old[prism_name])
    swap_jar(prism, jar_name, jar)

    entries = {
        f'SkyCraft-YS-{version}-MO2.zip': write_zip(mo2),
        f'SkyCraft-YS-{version}-Prism-instance.zip': write_zip(prism),
        f'отдельно/{jar_name}': jar,
        'отдельно/SkyCraft.dll': dll,
        'УСТАНОВКА-И-ПРОВЕРКА.md': (ROOT / 'docs/YS-COOP-RELEASE.md').read_bytes(),
        'docs/YS-STEAM.md': (ROOT / 'docs/YS-STEAM.md').read_bytes(),
        'docs/YS-COOP-PLAN.md': (ROOT / 'docs/YS-COOP-PLAN.md').read_bytes(),
        'LICENSE.txt': (ROOT / 'LICENSE').read_bytes(),
        'THIRD-PARTY-NOTICES.md': (ROOT / 'THIRD-PARTY-NOTICES.md').read_bytes(),
    }
    entries['SHA256.txt'] = ''.join(f'{hashlib.sha256(d).hexdigest()}  {n}\n' for n, d in sorted(entries.items())).encode()
    data = write_zip(entries)
    output.write_bytes(data)
    Path(str(output) + '.sha256').write_text(f'{hashlib.sha256(data).hexdigest()}  {output.name}\n', encoding='utf-8')
    print(f'{output} ({len(data) // 1024} KB), JAR {jar_name}')


if __name__ == '__main__':
    main()
