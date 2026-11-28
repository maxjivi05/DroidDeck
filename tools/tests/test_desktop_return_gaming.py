import os
import re
import shlex
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DESKTOP = ROOT / "tools/linuxfs/desktop"
SCRIPT = (DESKTOP / "droiddeck-desktop").read_text()
ENTRY = (DESKTOP / "droiddeck-return-gaming.desktop").read_text()


class DesktopReturnGamingTest(unittest.TestCase):
    def session(self, logout, rc=0, bottom=False):
        with tempfile.TemporaryDirectory() as tmp:
            run = Path(tmp, "run")
            launched = Path(tmp, "launched")
            exec_line = re.search(r"^Exec=(.*)$", ENTRY, re.M).group(1)
            inner = shlex.split(exec_line)[2].replace("dbus-send", "true")
            plasma = Path(tmp, "plasma")
            plasma.write_text("#!/bin/sh\n%s\nexit %d\n" % (inner if logout == "return" else ":", rc))
            plasma.chmod(0o755)
            steam = Path(tmp, "steam-launch")
            steam.write_text('#!/bin/sh\necho "$*" > %s\n' % launched)
            steam.chmod(0o755)
            tail = SCRIPT[SCRIPT.index("return_gaming="):]
            tail = tail.replace("dbus-run-session -- startplasma-wayland", str(plasma))
            tail = tail.replace("/usr/local/bin/droiddeck-pipewire stop", ":")
            tail = tail.replace("/usr/local/bin/droiddeck-steam-launch", str(steam))
            tail = tail.replace("sleep 30", ":")
            tail = tail.replace("/run/droiddeck", str(run))
            plasma.write_text(plasma.read_text().replace("/run/droiddeck", str(run)))
            env = {k: v for k, v in os.environ.items() if k != "DD_BOTTOM_DESKTOP"}
            if bottom:
                env["DD_BOTTOM_DESKTOP"] = "1"
            run.mkdir()
            (run / "return-gaming").write_text("")
            status = subprocess.run(["sh", "-c", tail], env=env, capture_output=True, text=True).returncode
            return status, launched.read_text().strip() if launched.exists() else None, (run / "return-gaming").exists()

    def test_return_to_gaming_mode_opens_steam(self):
        self.assertEqual(self.session("return"), (0, "bigpicture", False))

    def test_log_out_goes_back_without_steam_even_after_an_earlier_request(self):
        self.assertEqual(self.session("logout"), (0, None, False))

    def test_a_failed_session_and_the_bottom_desktop_never_open_steam(self):
        self.assertEqual(self.session("return", rc=1)[:2], (1, None))
        self.assertEqual(self.session("return", bottom=True)[:2], (0, None))

    def test_the_logout_request_outlives_starting_the_shutdown_service(self):
        exec_line = re.search(r"^Exec=(.*)$", ENTRY, re.M).group(1)
        call = shlex.split(shlex.split(exec_line)[2].split(";")[-1])
        self.assertEqual(call[0], "dbus-send")
        self.assertIn("--print-reply", call)

    def test_a_rejected_logout_request_forgets_the_return_to_gaming_mode(self):
        exec_line = re.search(r"^Exec=(.*)$", ENTRY, re.M).group(1)
        with tempfile.TemporaryDirectory() as tmp:
            inner = shlex.split(exec_line)[2].replace("/run/droiddeck", tmp)
            for sender, kept in (("true", True), ("false", False)):
                subprocess.run(["sh", "-c", inner.replace("dbus-send", sender, 1)], check=False)
                self.assertEqual(Path(tmp, "return-gaming").exists(), kept)

    def test_the_app_ships_the_shortcut_with_the_script(self):
        source = (ROOT / "app/src/main/java/com/droiddeck/launcher/session/SessionFiles.kt").read_text()
        self.assertIn('"usr/share/applications/droiddeck-return-gaming.desktop" to '
                      '"usr/share/applications/droiddeck-return-gaming.desktop"', source)
        self.assertIn("droiddeck-return-gaming.desktop", (ROOT / "tools/release/check_session_assets.py").read_text())


if __name__ == "__main__":
    unittest.main()
