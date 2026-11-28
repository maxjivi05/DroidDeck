import os
from pathlib import Path
import subprocess
import unittest

TOOLS = Path(__file__).resolve().parents[1]
DESKTOP = (TOOLS / "linuxfs/desktop/droiddeck-desktop").read_text()
PACKAGES = (TOOLS / "ubuntu/packages-desktop.txt").read_text().split()
SANDBOXES = ("MOZ_DISABLE_CONTENT_SANDBOX", "MOZ_SHM_NO_SEALS", "QTWEBENGINE_DISABLE_SANDBOX")


class DesktopRootAppsTest(unittest.TestCase):
    def desktop_environment(self):
        head = DESKTOP[:DESKTOP.index("\nexport KWIN_COMPOSE=")]
        env = {k: v for k, v in os.environ.items() if k not in SANDBOXES}
        probe = "".join('echo %s=${%s-unset}\n' % (name, name) for name in SANDBOXES)
        result = subprocess.run(["bash", "-c", head + "\nsh -c '" + probe + "'"], env=env,
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)
        return dict(line.split("=", 1) for line in result.stdout.splitlines())

    def test_web_engine_apps_start_as_root_like_firefox(self):
        self.assertEqual({name: "1" for name in SANDBOXES}, self.desktop_environment())

    def test_the_sandbox_switches_are_set_before_plasma(self):
        self.assertLess(DESKTOP.index("export QTWEBENGINE_DISABLE_SANDBOX=1"),
                        DESKTOP.index("dbus-run-session -- startplasma-wayland"))

    def test_software_sources_gets_the_modules_it_imports(self):
        self.assertIn("software-properties-qt", PACKAGES)
        for module in ("pyqt6-dev-tools", "ubuntu-drivers-common"):
            self.assertIn(module, PACKAGES)


if __name__ == "__main__":
    unittest.main()
