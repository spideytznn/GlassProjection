"""Build the bounded dual-display experiment; never modifies bundled helpers or installs it."""
import os
from pathlib import Path
import subprocess
import tempfile
import shutil

ROOT = Path(__file__).resolve().parents[2]

def main():
    java = Path(os.environ['JAVA_HOME']) / 'bin'
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
    suffix = '.exe' if os.name == 'nt' else ''
    output = ROOT / 'build/diagnostics/dual-display-live'
    output.mkdir(parents=True, exist_ok=True)
    sources = list((ROOT / 'tools/experiments').glob('*.java'))
    sources += [ROOT / 'tools/helpers' / (name + '.java') for name in
                ('LiveMirrorWindowProbe', 'LiveBlurPyramid', 'FoldReturnMotion', 'OutputOwnerGuard', 'RenderIdleGate', 'RenderWakeSignal', 'RenderFrameCache', 'RenderDrawGate')]
    sources += [ROOT / 'projection-lab/src/main/java/io/github/sixzleo/tabfold/projection' / (name + '.java')
                for name in ('ProjectionMath', 'ProjectionEntrance', 'ProjectionAngleMotion', 'AdaptiveAngleFollow', 'FrameUpdateOrder', 'CoverLayoutReady', 'ScreenFade')]
    with tempfile.TemporaryDirectory(prefix='compile-', dir=output) as temp:
        classes, dex = Path(temp) / 'classes', Path(temp) / 'dex'
        classes.mkdir(); dex.mkdir()
        subprocess.run([str(java / ('javac' + suffix)), '-encoding', 'UTF-8', '-cp', str(sdk / 'platforms/android-35/android.jar'), '-d', str(classes), *map(str, sources)], check=True)
        subprocess.run([str(java / ('java' + suffix)), '-cp', str(sdk / 'build-tools/35.0.0/lib/d8.jar'), 'com.android.tools.r8.D8', '--min-api', '33', '--output', str(dex), *map(str, sorted(classes.rglob('*.class')))], check=True)
        shutil.copyfile(dex / 'classes.dex', output / 'classes.dex')
    print(output / 'classes.dex')

if __name__ == '__main__':
    main()
