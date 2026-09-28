import json
import os
from pathlib import Path
import runpy
import shlex
import subprocess
import sys
import tempfile
import unittest

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
MODULE = runpy.run_path(str(BIN / "bannerlator-game-env"))
COMPAT = runpy.run_path(str(BIN / "bannerlator-steam-compat"))


class GameEnvironmentTest(unittest.TestCase):
    def test_profile_precedence_and_unset(self):
        env = {"KEEP": "inherited", "REMOVE": "inherited", "CUSTOM": "launch option"}
        config = {"version": 1, "shared": {"CUSTOM": "shared"}, "games": {"42": {"REMOVE": None, "CUSTOM": "game", "EMPTY": ""}}}
        self.assertEqual(MODULE["apply_config"](env, config, "42")[0], {"KEEP": "inherited", "CUSTOM": "game", "EMPTY": ""})
        self.assertEqual(env["REMOVE"], "inherited")
        self.assertEqual(MODULE["apply_config"](env, config, "43")[0]["CUSTOM"], "shared")

    def test_defaults_fill_only_what_the_launch_leaves_unset(self):
        env = {"VKD3D_SHADER_MODEL": "6_5", "TU_DEBUG": "gmem"}
        config = {"version": 1, "defaults": {"VKD3D_SHADER_MODEL": "6_6", "TU_DEBUG": "sysmem", "VKD3D_FEATURE_LEVEL": "12_2"},
                  "shared": {"TU_DEBUG": "nolrz"}, "games": {"42": {"VKD3D_FEATURE_LEVEL": None}}}
        result, names = MODULE["apply_config"](env, config, "42")
        self.assertEqual(result, {"VKD3D_SHADER_MODEL": "6_5", "TU_DEBUG": "nolrz"})
        self.assertEqual(names, ["TU_DEBUG", "VKD3D_FEATURE_LEVEL", "VKD3D_SHADER_MODEL"])
        self.assertEqual(MODULE["apply_config"]({}, config, "43")[0], {"VKD3D_SHADER_MODEL": "6_6", "TU_DEBUG": "nolrz", "VKD3D_FEATURE_LEVEL": "12_2"})
        with self.assertRaises(ValueError):
            MODULE["apply_config"]({}, {"version": 1, "defaults": {"A": None}}, "42")

    def test_proton_log_lands_beside_the_session_logs(self):
        config = {"version": 1, "games": {"42": {"PROTON_LOG": "1"}}}
        result, names = MODULE["apply_config"]({"BL_DEBUG_DIR": "/logs/session-1"}, config, "42")
        self.assertEqual(result["PROTON_LOG_DIR"], "/logs/session-1")
        self.assertIn("PROTON_LOG_DIR", names)
        chosen = MODULE["apply_config"]({"BL_DEBUG_DIR": "/logs", "PROTON_LOG_DIR": "/mine"}, config, "42")[0]
        self.assertEqual(chosen["PROTON_LOG_DIR"], "/mine")
        self.assertNotIn("PROTON_LOG_DIR", MODULE["apply_config"]({"BL_DEBUG_DIR": "/logs"}, config, "43")[0])
        unset = {"version": 1, "shared": {"PROTON_LOG_DIR": None}, "games": {"42": {"PROTON_LOG": "1"}}}
        self.assertNotIn("PROTON_LOG_DIR", MODULE["apply_config"]({"BL_DEBUG_DIR": "/logs"}, unset, "42")[0])

    def test_invalid_configuration_is_atomic(self):
        env = {"ORIGINAL": "unchanged"}
        for entries in ({"A": "ok", "BAD=KEY": "x"}, {"A": "bad\0value"}, {"A": 1}):
            with self.assertRaises(ValueError):
                MODULE["apply_config"](env, {"version": 1, "shared": entries}, "42")
            self.assertEqual(env, {"ORIGINAL": "unchanged"})

    def test_game_ids_and_probes(self):
        for prefix in ("", "/compatdata/0", "/compatdata/0-123", "/compatdata/nope", "/compatdata/4294967296"):
            self.assertIsNone(MODULE["game_id"]({"STEAM_COMPAT_DATA_PATH": prefix}))
        self.assertEqual(MODULE["game_id"]({"STEAM_COMPAT_DATA_PATH": "/a/compatdata/42/"}), "42")
        self.assertEqual(MODULE["game_id"]({"STEAM_COMPAT_DATA_PATH": "/a/compatdata/-1"}), "4294967295")

    def test_launch_reads_updates_preserves_argv_and_does_not_execute_values(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            config = home / ".config/droiddeck/game-environment.json"
            config.parent.mkdir(parents=True)
            probe = home / "fake-proton"
            probe.write_text("#!/usr/bin/python3\nimport json, os, sys\nprint(json.dumps([os.environ.get('CUSTOM'), sys.argv[1:]]))\n")
            probe.chmod(0o755)
            env = {**os.environ, "HOME": tmp, "STEAM_COMPAT_DATA_PATH": "/compatdata/42"}
            for value in ("first value", "$(touch " + str(home / "injected") + "); 'literal'=value"):
                config.write_text(json.dumps({"version": 1, "shared": {"CUSTOM": value}}))
                result = subprocess.run([sys.executable, str(BIN / "bannerlator-game-env"), str(probe), "waitforexitandrun", "path with spaces", "a=b"],
                    env=env, text=True, capture_output=True, check=True)
                self.assertEqual(json.loads(result.stdout), [value, ["waitforexitandrun", "path with spaces", "a=b"]])
                self.assertEqual(result.stderr, "bannerlator-game-env: app 42: CUSTOM=" + shlex.quote(value) + "\n")
            self.assertFalse((home / "injected").exists())
            for verb, prefix in (("run", "/compatdata/42"), ("waitforexitandrun", "/compatdata/0")):
                output = subprocess.check_output([sys.executable, str(BIN / "bannerlator-game-env"), str(probe), verb], env={**env, "STEAM_COMPAT_DATA_PATH": prefix, "CUSTOM": "original"}, text=True)
                self.assertEqual(json.loads(output)[0], "original")
            config.write_text("{broken")
            result = subprocess.run([sys.executable, str(BIN / "bannerlator-game-env"), str(probe), "waitforexitandrun"], env={**env, "CUSTOM": "original"}, text=True, capture_output=True, check=True)
            self.assertEqual(json.loads(result.stdout)[0], "original")

    def test_generated_valve_and_third_party_launchers_apply_configuration(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            steam = home / "Steam"
            tools = steam / "compatibilitytools.d"
            depot = steam / "steamapps/common" / COMPAT["SOURCES"][0]
            (depot / "files/bin-arm64").mkdir(parents=True)
            extra = tools / "custom-proton"
            extra.mkdir(parents=True)
            for base in (depot, extra):
                proton = base / "proton"
                proton.write_text("#!/usr/bin/python3\nimport json, os, sys\nprint(json.dumps([os.environ['CUSTOM'], sys.argv[1:], "
                    "os.environ.get('LD_LIBRARY_PATH'), os.environ.get('PROTON_USE_PIPEWIRE')]))\nsys.exit(7)\n")
                proton.chmod(0o755)
            (extra / "toolmanifest.vdf").write_text('"manifest" { "commandline" "/proton %verb%" }')
            COMPAT["build_tool"](str(tools / COMPAT["TOOL"]))
            COMPAT["adopt_extras"](str(tools))
            config = home / ".config/droiddeck/game-environment.json"
            config.parent.mkdir(parents=True)
            config.write_text(json.dumps({"version": 1, "shared": {"CUSTOM": "shared"}, "games": {"42": {"CUSTOM": "specific"}}}))
            wrappers = (tools / COMPAT["TOOL"] / COMPAT["LAUNCHER"], extra / COMPAT["EXTRA_WRAPPER"])
            for wrapper in wrappers:
                wrapper.write_text(wrapper.read_text().replace("/usr/local/bin/bannerlator-game-env", str(BIN / "bannerlator-game-env")))
                result = subprocess.run([str(wrapper), "waitforexitandrun", "game with spaces.exe"],
                    env={"PATH": os.defpath, "HOME": tmp, "STEAM_COMPAT_DATA_PATH": "/compatdata/42", "STEAM_COMPAT_CLIENT_INSTALL_PATH": str(steam),
                         "LD_LIBRARY_PATH": str(steam / "steamrtarm64") + ":/usr/lib/extra:" + str(steam / "steamrtarm64/panorama")},
                    text=True, capture_output=True)
                self.assertEqual(result.returncode, 7, result.stderr)
                self.assertEqual(json.loads(result.stdout), ["specific", ["waitforexitandrun", "game with spaces.exe"], "/usr/lib/extra", "0"])

    def test_both_proton_wrappers_call_environment_launcher(self):
        for script in (COMPAT["LAUNCHER_SH"], COMPAT["EXTRA_WRAPPER_SH"] % "proton"):
            subprocess.run(["bash", "-n"], input=script, text=True, check=True)
            self.assertIn('exec ${BL_TASKSET:-} /usr/local/bin/bannerlator-game-env', script)


if __name__ == "__main__":
    unittest.main()
