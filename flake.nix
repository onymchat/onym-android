{
  description = "onym-android: a reproducible NixOS dev environment (Android SDK 36 + emulator + Gradle/JDK 17)";

  # nixos-26.05 is the line the host system itself runs. Why a branch and not
  # `nixpkgs-unstable`: the androidenv packages (platform-tools, emulator,
  # system-image, ~2.5 GB) are built once for this line and reused from /nix/store.
  #
  # Pin the exact revision: commit flake.lock (it is already there).
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-26.05";

  outputs = { self, nixpkgs }:
    let
      system = "x86_64-linux";

      # allowUnfree is mandatory: the Android SDK is unfree, and its
      # sub-package names are dynamic (platform-tools_r37.0.0-linux.zip,
      # x86_64-36_r*.zip), so substring predicates never match them.
      pkgs = import nixpkgs {
        inherit system;
        config = {
          android_sdk.accept_license = true;
          allowUnfree = true;
        };
      };

      apiLevel = "36";          # = compileSdk/targetSdk in app/build.gradle.kts
      minBuildTools = "36.0.0"; # headroom: AGP 8.11 asks for 35.0.0 by default
      avdName = "onym_api${apiLevel}";

      androidSdk = pkgs.androidenv.composeAndroidPackages {
        includeEmulator = true;
        includeSystemImages = true;
        useGoogleAPIs = true; # without it the google_apis image does not link
        platformVersions = [ apiLevel ];
        systemImageTypes = [ "google_apis" ];
        abiVersions = [ "x86_64" ];
        buildToolsVersions = [ "35.0.0" minBuildTools ];
      };

      sdkRoot = "${androidSdk.androidsdk}/libexec/android-sdk";

      # --- what goes inside the FHS container ------------------------------
      # The scripts live INSIDE the FHS: that way nothing has to be forwarded
      # through `-c`, and `./gradlew` behaves like a plain local run.
      helperScripts = p:
        let
          mk = name: text: p.writeShellScriptBin name text;
        in [
          (mk "onym-doctor" ''
            set -u
            echo "ANDROID_HOME = $ANDROID_HOME"
            echo "JAVA_HOME    = $JAVA_HOME"
            echo "AVD_HOME     = ''${ANDROID_AVD_HOME:-}"
            echo
            echo "--- java"; java -version 2>&1 | head -2
            echo "--- sdk platforms"; ls "$ANDROID_HOME/platforms" 2>/dev/null || echo "(none)"
            echo "--- build-tools"; ls "$ANDROID_HOME/build-tools" 2>/dev/null || echo "(none)"
            echo "--- system-images"; ls "$ANDROID_HOME/system-images"/*/*/ 2>/dev/null | head -8 || echo "(none)"
            echo "--- adb"; "$ANDROID_HOME/platform-tools/adb" version 2>&1 | head -2
            echo "--- emulator"; "$ANDROID_HOME/emulator/emulator" -version 2>&1 | head -2
            echo "--- kvm"; ls -l /dev/kvm 2>&1
            echo "--- /sys"; ls -d /sys >/dev/null 2>&1 && echo "ok" || echo "MISSING: QEMU/emulator will crash (needs --ro-bind-try /sys /sys)"
            echo "--- AVD"; ls "''${ANDROID_AVD_HOME:-$HOME/.android/avd}" 2>/dev/null || echo "(none)"
            echo "--- gradle wrapper"; grep distributionUrl gradle/wrapper/gradle-wrapper.properties 2>/dev/null || echo "(not in the project root)"
            if grep -rql 'git-lfs.github.com/spec' --include='*.png' app/src/main/res 2>/dev/null; then
              echo "--- LFS: WARNING, the icons are git-lfs pointers. Run: onym-lfs"
            else
              echo "--- LFS: ok"
            fi
          '')

          (mk "onym-lfs" ''
            set -eu
            root="$(git rev-parse --show-toplevel 2>/dev/null || echo .)"
            git -C "$root" lfs install --local >/dev/null 2>&1 || true
            git -C "$root" lfs pull
            echo "LFS: assets materialised"
            echo
            echo "WARNING: git lfs install --local put a filter into .git/config."
            echo "Every git invocation outside this shell now needs git-lfs too, or"
            echo "any work with LFS paths fails with \"git-lfs: command not found\"."
            echo "Install it into the profile:  nix profile install nixpkgs#git-lfs"
          '')

          # Create the AVD by hand: `avdmanager create avd -d <device>` on Nix
          # dies with "Could not load devices.xml" (device specs live outside the
          # expected SDK tree). The config.ini below is the known-good shape.
          (mk "onym-avd" ''
            set -eu
            name="''${AVD_NAME:-${avdName}}"
            api="${apiLevel}"
            sysdir="system-images/android-$api/google_apis/x86_64"
            if [ ! -d "$ANDROID_HOME/$sysdir" ]; then
              echo "No system image: $ANDROID_HOME/$sysdir" >&2
              echo "Add it to the flake's platformVersions/systemImageTypes and rebuild." >&2
              exit 1
            fi
            base="''${ANDROID_AVD_HOME:-$HOME/.android/avd}"
            dir="$base/$name.avd"
            if [ -d "$dir" ]; then echo "AVD $name already exists: $dir"; exit 0; fi
            mkdir -p "$dir"
            cat > "$dir/config.ini" <<EOF
avd.ini.encoding=UTF-8
abi.type=x86_64
avd.ini.displayname=$name
disk.dataPartition.size=6G
hw.accelerometer=yes
hw.audioInput=no
hw.audioOutput=no
hw.battery=yes
hw.camera.back=virtualscene
hw.camera.front=emulated
hw.cpu.arch=x86_64
hw.cpu.ncore=4
hw.device.name=pixel_6
hw.dPad=no
hw.gps=yes
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
hw.keyboard=yes
hw.lcd.density=420
hw.lcd.height=2400
hw.lcd.width=1080
hw.mainKeys=no
hw.ramSize=3072
hw.sdCard=no
hw.touch.screen=yes
image.sysdir.1=$sysdir/
tag.display=Google APIs
tag.id=google_apis
vm.heapSize=512
EOF
            touch "$dir/userdata.img"
            cat > "$base/$name.ini" <<EOF
avd.ini.encoding=UTF-8
path=$dir
path.rel=avd/$name.avd
target=android-$api
EOF
            echo "AVD $name created: $dir"
            echo "Start it with: onym-emu"
          '')

          # The emulator is the only reason the whole toolchain lives in an FHS
          # env (it needs FHS libraries) and the only reason /dev/kvm matters.
          #
          (mk "onym-emu" ''
            set -eu
            name="''${AVD_NAME:-${avdName}}"
            port="''${EMU_PORT:-5556}"
            serial="emulator-$port"
            if ! [ -w /dev/kvm ]; then
              echo "No access to /dev/kvm — the emulator will not start." >&2
              exit 1
            fi
            if "$ANDROID_HOME/platform-tools/adb" -s "$serial" get-state >/dev/null 2>&1; then
              echo "Emulator already running: $serial"; exit 0
            fi
            log="''${ANDROID_USER_HOME:-$HOME/.android}/onym-emulator-$port.log"
            win="''${ONYM_EMU_WINDOW:-0}"
            args=""
            [ "$win" = "1" ] || args="-no-window"
            echo "Starting $name on $serial (log: $log)"
            "$ANDROID_HOME/emulator/emulator" -avd "$name" -port "$port" \
              -gpu swiftshader_indirect -no-snapshot -no-audio -no-boot-anim \
              $args >"$log" 2>&1 &
            echo "Waiting for boot..."
            "$ANDROID_HOME/platform-tools/adb" -s "$serial" wait-for-device
            i=0
            while [ "$("$ANDROID_HOME/platform-tools/adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do
              i=$((i+1))
              if [ "$i" -gt 300 ]; then
                echo "Did not boot within 300 s. See $log" >&2; exit 1
              fi
              sleep 1
            done
            echo "Ready: $serial"
          '')

          (mk "onym-emu-stop" ''
            set -u
            serial="emulator-''${EMU_PORT:-5556}"
            "$ANDROID_HOME/platform-tools/adb" -s "$serial" emu kill 2>/dev/null || echo "not running: $serial"
          '')

          # Gradle runs only inside the FHS: the AAPT2 it pulls from Maven is a
          # plain ELF for /lib64/ld-linux and dies in a bare devShell
          # "Daemon startup failed".
          (mk "onym-build" ''
            set -eu
            ./gradlew ''${*:-assembleDebug} --no-daemon --console=plain
            # Print the APK of the variant that was asked for: the variants
            # live in different directories, and "newest file in the tree" is
            # not the same thing as "just built".
            first="''${1:-assembleDebug}"
            variant="$(printf '%s' "$first" | sed -n 's/^assemble//p' | tr '[:upper:]' '[:lower:]')"
            apk="$(ls app/build/outputs/apk/"''${variant:-debug}"/*.apk 2>/dev/null | head -1 || true)"
            [ -n "$apk" ] || apk="$(ls -t app/build/outputs/apk/*/*.apk 2>/dev/null | head -1 || true)"
            [ -n "$apk" ] && echo "APK: $apk"
          '')

          (mk "onym-test" ''
            set -eu
            ./gradlew ''${*:-testDebugUnitTest} --no-daemon --console=plain
          '')

          (mk "onym-lint" ''
            set -eu
            python3 scripts/lint-secrets.py
            python3 scripts/lint-no-secret-transport.py
            ./gradlew :app:lintDebug --no-daemon --console=plain
          '')

          # connectedDebugAndroidTest needs a booted emulator (onym-emu);
          # this project has no emulator CI job.
          (mk "onym-ui-test" ''
            set -eu
            serial="emulator-''${EMU_PORT:-5556}"
            export ANDROID_SERIAL="$serial"
            "$ANDROID_HOME/platform-tools/adb" -s "$serial" get-state >/dev/null
            ./gradlew ''${*:-connectedDebugAndroidTest} --no-daemon --console=plain
          '')

          (mk "onym-install" ''
            set -eu
            serial="emulator-''${EMU_PORT:-5556}"
            "$ANDROID_HOME/platform-tools/adb" -s "$serial" install -r -d \
              app/build/outputs/apk/debug/app-debug.apk
            "$ANDROID_HOME/platform-tools/adb" -s "$serial" shell am start \
              -n app.onym.android/.MainActivity
          '')

          # Install onto a real phone rather than the emulator: `adb -d` takes
          # the only USB device, so it does not matter what else is hanging in
          # `adb devices`. Defaults to the trial build: it installs next to the
          # daily one and touches nothing in it.
          (mk "onym-install-phone" ''
            set -eu
            apk="''${1:-app/build/outputs/apk/trial/app-trial.apk}"
            [ -f "$apk" ] || { echo "No such file: $apk (run onym-build assembleTrial first)" >&2; exit 1; }
            adb_bin="$ANDROID_HOME/platform-tools/adb"
            if [ -z "$("$adb_bin" devices | awk 'NR>1 && $2=="device"{print $1; exit}')" ]; then
              echo "adb sees no device at all:" >&2
              "$adb_bin" devices -l >&2
              echo >&2
              echo "Phone over USB: no access to the device. On nixpkgs 26.05 the" >&2
              echo "programs.adb option is GONE — use this instead:" >&2
              echo "  environment.systemPackages = with pkgs; [ android-tools ];" >&2
              echo "the uaccess rules come from systemd (70-uaccess.rules); no group needed." >&2
              echo "The phone must be in File transfer mode, otherwise uaccess never" >&2
              echo "tags it. Without USB: Wireless debugging -> adb pair <ip>:<port>." >&2
              exit 1
            fi
            echo "Installing $apk, built $(date -r "$apk" '+%Y-%m-%d %H:%M')"
            "$adb_bin" -d install -r "$apk"
            echo "Installed: $apk"
          '')
        ];

      fhsName = "onym-android-fhs";

      # Container entry point. runScript receives the argv, so both modes work:
      # `onym-android-fhs` (interactive) and
      # `onym-android-fhs -c 'command'`.
      fhs = pkgs.buildFHSEnv {
        name = fhsName;
        # QEMU/the emulator reads /sys/devices/system/cpu (frequencies, online CPUs).
        # Inside an FHS container /sys is not mounted at all, and the qemu threads
        # stall with "detected a hanging thread" — so bind it through.
        extraBwrapArgs = [ "--ro-bind-try" "/sys" "/sys" ];
        runScript = pkgs.writeShellScript "onym-android-init" ''
          export ANDROID_HOME="${sdkRoot}"
          export ANDROID_SDK_ROOT="${sdkRoot}"
          export ANDROID_USER_HOME="''${ANDROID_USER_HOME:-$HOME/.android}"
          export ANDROID_AVD_HOME="''${ANDROID_AVD_HOME:-$ANDROID_USER_HOME/avd}"
          export ANDROID_EMULATOR_HOME="''${ANDROID_EMULATOR_HOME:-$ANDROID_USER_HOME}"
          export JAVA_HOME="${pkgs.jdk17}"
          export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
          # The Gradle daemon outlives the container and holds its mount namespace.
          export GRADLE_OPTS="''${GRADLE_OPTS:--Dorg.gradle.daemon=false}"

          if [ "$#" -eq 0 ]; then
            cat <<'BANNER'
  onym-android — Android environment (SDK 36, JDK 17, emulator)
  Commands: onym-doctor | onym-build | onym-test | onym-lint
            onym-avd | onym-emu | onym-emu-stop | onym-ui-test | onym-install
            onym-install-phone | onym-lfs (materialise git-lfs assets)
BANNER
          fi
          exec bash "$@"
        '';

        targetPkgs = p: [
          p.bash
          p.coreutils
          p.jdk17
          p.git
          p.git-lfs
          p.python3
          p.jq
          p.unzip
          p.zip
          p.which
          p.file
          p.curl
          p.cacert
          androidSdk.androidsdk
        ] ++ helperScripts p;

        # Libraries missing from the FHS root: expected by emulator/adb/aapt2 and
        # by the emulator's Qt/GL stack.
        multiPkgs = p: with p; [
          zlib
          glib
          libGL
          fontconfig
          freetype
          alsa-lib
          libpulseaudio
          nss
          libxkbcommon
          libdrm
          stdenv.cc.cc.lib
          libx11
          libxext
          libxrender
          libxrandr
          libxcursor
          libxi
          libxcb
          mesa
          vulkan-loader
        ];
      };

      # `nix run` helpers: each just enters the container and calls its script.
      # Arguments are forwarded explicitly: the container runs `bash -c <string>`,
      # not `bash -c <string> "$@"`, so without this quoting
      # `nix run .#build assembleTrial` silently lost the task and built debug.
      runApp = script: {
        type = "app";
        program = "${pkgs.writeShellScript "onym-run-${script}" ''
          cmd=${pkgs.lib.escapeShellArg script}
          for arg in "$@"; do
            cmd="$cmd $(printf '%q' "$arg")"
          done
          exec ${fhs}/bin/${fhsName} -c "$cmd"
        ''}";
      };
    in
    {
      # The flake schema needs the system level: <output>.<system>[.<name>].
      packages.x86_64-linux = {
        sdk = androidSdk.androidsdk;
        fhs = fhs;
        default = androidSdk.androidsdk;
      };

      # nix develop -> straight into the FHS: ./gradlew and the emulator work unwrapped.
      devShells.x86_64-linux.default = fhs.env;

      apps.x86_64-linux = {
        build = runApp "onym-build";
        test = runApp "onym-test";
        lint = runApp "onym-lint";
        avd = runApp "onym-avd";
        emu = runApp "onym-emu";
        ui-test = runApp "onym-ui-test";
        install-phone = runApp "onym-install-phone";
        doctor = runApp "onym-doctor";
        default = runApp "onym-doctor";
      };
    };
}
