"""Run existing geometry, display-direction and scene-gating tests with JDK 17."""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SUFFIX = ".exe" if os.name == "nt" else ""


def main():
    java_home = os.environ.get("JAVA_HOME")
    if not java_home:
        raise SystemExit("Set JAVA_HOME to JDK 17.")
    java = Path(java_home) / "bin" / ("java" + SUFFIX)
    javac = Path(java_home) / "bin" / ("javac" + SUFFIX)
    package = "io.github.sixzleo.tabfold.projection"
    source = ROOT / "projection-lab/src/main/java" / package.replace(".", "/")
    files = [source / f"{name}.java" for name in ("FixedDualPolicy", "ProjectionMath", "ProjectionEntrance", "ProjectionAngleMotion", "ProjectionSceneTiming", "AdaptiveAngleFollow", "FrameUpdateOrder", "ProjectionCadence", "CoverBlackout", "ScreenFade", "CoverLayoutReady", "FrameGate", "LockScreenGate", "FoldHoldGate", "FingerSwipeGate", "NavigationGestureGate", "FoldPose")]
    files += [ROOT / "tools/helpers/EarlyDisplayModel.java", ROOT / "tools/helpers/FoldReturnMotion.java", ROOT / "tools/helpers/RenderIdleGate.java", ROOT / "tools/helpers/RenderWakeSignal.java", ROOT / "tools/helpers/RenderDrawGate.java"]
    files += [source / name for name in ("AppBlacklist.java", "UpdateTrust.java", "UpdateTransport.java", "ResumableUpdate.java", "HomeLayout.java", "HomeWidgetResize.java")]
    files += sorted((ROOT / "tools/tests").glob("*.java"))
    build = ROOT / "build"
    build.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="models-", dir=build) as output:
        subprocess.run([str(javac), "-encoding", "UTF-8", "-d", output, *map(str, files)], check=True)
        tests = [package + "." + name for name in ("ProjectionMathTest", "ProjectionEntranceTest", "ProjectionAngleMotionTest", "CoverBlackoutTest", "ScreenFadeTest", "FrameGateTest", "LockScreenGateTest", "FoldHoldGateTest", "FingerSwipeGateTest", "NavigationGestureGateTest", "FoldPoseTest", "FoldClosureLatchTest", "FoldDirectContactTest")]
        tests += [package+".AppBlacklistTest", package+".PerformancePolicyTest", "io.github.sixzleo.tabfold.probe.EarlyDisplayModelTest", "io.github.sixzleo.tabfold.probe.FoldReturnMotionTest", "io.github.sixzleo.tabfold.probe.RenderIdleGateTest", "io.github.sixzleo.tabfold.probe.RenderDrawGateTest"]
        tests.append("FixedDualPolicyTest")
        for test in tests:
            subprocess.run([str(java), "-cp", output, test], check=True)
        subprocess.run([str(java), "-cp", output, package + ".UpdateSecurityTest"], check=True)
        subprocess.run([str(java), "-cp", output, package + ".ResumeDownloadTest"], check=True)
        subprocess.run([str(java), "-cp", output, package + ".HomeLayoutTest"], check=True)
        subprocess.run([str(java), "-cp", output, package + ".HomeWidgetResizeTest"], check=True)


if __name__ == "__main__":
    main()
