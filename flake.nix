{
  description = "onym-android: воспроизводимое окружение разработки под NixOS (Android SDK 36 + эмулятор + Gradle/JDK 17)";

  # Ветка nixos-26.05 — та же линия, что у системы. Причина выбора ветки, а не
  # `nixpkgs-unstable`: androidenv-пакеты (platform-tools, emulator, system-image,
  # ~2.5 ГБ) собираются один раз под эту линию и переиспользуются из /nix/store.
  #
  # Зафиксировать точную ревизию: закоммитьте flake.lock (он уже есть).
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-26.05";

  outputs = { self, nixpkgs }:
    let
      system = "x86_64-linux";

      # allowUnfree обязателен: Android SDK — unfree, а имена его
      # под-пакетов динамические (platform-tools_r37.0.0-linux.zip,
      # x86_64-36_r*.zip), поэтому предикаты по подстроке их не ловят.
      pkgs = import nixpkgs {
        inherit system;
        config = {
          android_sdk.accept_license = true;
          allowUnfree = true;
        };
      };

      apiLevel = "36";          # = compileSdk/targetSdk в app/build.gradle.kts
      minBuildTools = "36.0.0"; # запас: AGP 8.11 по умолчанию просит 35.0.0
      avdName = "onym_api${apiLevel}";

      androidSdk = pkgs.androidenv.composeAndroidPackages {
        includeEmulator = true;
        includeSystemImages = true;
        useGoogleAPIs = true; # без этого google_apis-образ не линкуется
        platformVersions = [ apiLevel ];
        systemImageTypes = [ "google_apis" ];
        abiVersions = [ "x86_64" ];
        buildToolsVersions = [ "35.0.0" minBuildTools ];
      };

      sdkRoot = "${androidSdk.androidsdk}/libexec/android-sdk";

      # --- что кладём внутрь FHS-контейнера -------------------------------
      # Скрипты живут ВНУТРИ FHS: тогда им не нужно прокидывать окружение
      # через `-c`, и `./gradlew` работает как обычный локальный запуск.
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
            echo "--- sdk-платформы"; ls "$ANDROID_HOME/platforms" 2>/dev/null || echo "(нет)"
            echo "--- build-tools"; ls "$ANDROID_HOME/build-tools" 2>/dev/null || echo "(нет)"
            echo "--- system-images"; ls "$ANDROID_HOME/system-images"/*/*/ 2>/dev/null | head -8 || echo "(нет)"
            echo "--- adb"; "$ANDROID_HOME/platform-tools/adb" version 2>&1 | head -2
            echo "--- emulator"; "$ANDROID_HOME/emulator/emulator" -version 2>&1 | head -2
            echo "--- kvm"; ls -l /dev/kvm 2>&1
            echo "--- /sys"; ls -d /sys >/dev/null 2>&1 && echo "ok" || echo "ОТСУТСТВУЕТ: QEMU/эмулятор упадёт (нужен --ro-bind-try /sys /sys)"
            echo "--- AVD"; ls "''${ANDROID_AVD_HOME:-$HOME/.android/avd}" 2>/dev/null || echo "(нет)"
            echo "--- gradle wrapper"; grep distributionUrl gradle/wrapper/gradle-wrapper.properties 2>/dev/null || echo "(не в корне проекта)"
            if grep -rql 'git-lfs.github.com/spec' --include='*.png' app/src/main/res 2>/dev/null; then
              echo "--- LFS: ВНИМАНИЕ, иконки — указатели git-lfs. Запустите: onym-lfs"
            else
              echo "--- LFS: ok"
            fi
          '')

          (mk "onym-lfs" ''
            set -eu
            root="$(git rev-parse --show-toplevel 2>/dev/null || echo .)"
            git -C "$root" lfs install --local >/dev/null 2>&1 || true
            git -C "$root" lfs pull
            echo "LFS: файлы выгружены"
            echo
            echo "ВНИМАНИЕ: git lfs install --local прописал фильтр в .git/config."
            echo "Теперь git вне этого шелла тоже должен находить git-lfs, иначе"
            echo "любая работа с LFS-файлами падает с \"git-lfs: command not found\"."
            echo "Поставьте его в профиль:  nix profile install nixpkgs#git-lfs"
          '')

          # AVD создаём вручную: `avdmanager create avd -d <device>` на Nix
          # падает с "Could not load devices.xml" (device-специи лежат вне
          # ожидаемого SDK-дерева). config.ini ниже — проверенный вариант.
          (mk "onym-avd" ''
            set -eu
            name="''${AVD_NAME:-${avdName}}"
            api="${apiLevel}"
            sysdir="system-images/android-$api/google_apis/x86_64"
            if [ ! -d "$ANDROID_HOME/$sysdir" ]; then
              echo "Нет system-image: $ANDROID_HOME/$sysdir" >&2
              echo "Добавьте в flake platformVersions/systemImageTypes и пересоберите." >&2
              exit 1
            fi
            base="''${ANDROID_AVD_HOME:-$HOME/.android/avd}"
            dir="$base/$name.avd"
            if [ -d "$dir" ]; then echo "AVD $name уже есть: $dir"; exit 0; fi
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
            echo "AVD $name создан: $dir"
            echo "Запуск: onym-emu"
          '')

          # Эмулятор — единственная причина, по которой весь тулчейн живёт
          # в FHS (ему нужны FHS-библиотеки), и единственная причина, по
          # которой нужен /dev/kvm.
          (mk "onym-emu" ''
            set -eu
            name="''${AVD_NAME:-${avdName}}"
            port="''${EMU_PORT:-5556}"
            serial="emulator-$port"
            if ! [ -w /dev/kvm ]; then
              echo "Нет доступа к /dev/kvm — эмулятор не стартует." >&2
              exit 1
            fi
            if "$ANDROID_HOME/platform-tools/adb" -s "$serial" get-state >/dev/null 2>&1; then
              echo "Эмулятор уже работает: $serial"; exit 0
            fi
            log="''${ANDROID_USER_HOME:-$HOME/.android}/onym-emulator-$port.log"
            win="''${ONYM_EMU_WINDOW:-0}"
            args=""
            [ "$win" = "1" ] || args="-no-window"
            echo "Старт $name на $serial (лог: $log)"
            "$ANDROID_HOME/emulator/emulator" -avd "$name" -port "$port" \
              -gpu swiftshader_indirect -no-snapshot -no-audio -no-boot-anim \
              $args >"$log" 2>&1 &
            echo "Ждём загрузку..."
            "$ANDROID_HOME/platform-tools/adb" -s "$serial" wait-for-device
            i=0
            while [ "$("$ANDROID_HOME/platform-tools/adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do
              i=$((i+1))
              if [ "$i" -gt 300 ]; then
                echo "Не загрузился за 300 c. Смотрите $log" >&2; exit 1
              fi
              sleep 1
            done
            echo "Готов: $serial"
          '')

          (mk "onym-emu-stop" ''
            set -u
            serial="emulator-''${EMU_PORT:-5556}"
            "$ANDROID_HOME/platform-tools/adb" -s "$serial" emu kill 2>/dev/null || echo "не запущен: $serial"
          '')

          # Gradle — только внутри FHS: собственный AAPT2 из Maven — обычный
          # ELF под /lib64/ld-linux, в чистом devShell падает
          # "Daemon startup failed".
          (mk "onym-build" ''
            set -eu
            ./gradlew ''${*:-assembleDebug} --no-daemon --console=plain
            echo "APK: app/build/outputs/apk/debug/app-debug.apk"
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

          # connectedDebugAndroidTest требует загруженного эмулятора
          # (onym-emu) — CI-джобы с эмулятором в проекте нет.
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
        ];

      fhsName = "onym-android-fhs";

      # Точка входа в контейнер. runScript получает аргументы от chiral,
      # поэтому работают оба режима: `onym-android-fhs` (интерактивно) и
      # `onym-android-fhs -c 'команда'`.
      fhs = pkgs.buildFHSEnv {
        name = fhsName;
        # QEMU/эмулятор читает /sys/devices/system/cpu (частоты, online-ядра).
        # В FHS-контейнере /sys не монтируется вообще, и qemu-потоки
        # встают с "detected a hanging thread" — поэтому пробрасываем.
        extraBwrapArgs = [ "--ro-bind-try" "/sys" "/sys" ];
        runScript = pkgs.writeShellScript "onym-android-init" ''
          export ANDROID_HOME="${sdkRoot}"
          export ANDROID_SDK_ROOT="${sdkRoot}"
          export ANDROID_USER_HOME="''${ANDROID_USER_HOME:-$HOME/.android}"
          export ANDROID_AVD_HOME="''${ANDROID_AVD_HOME:-$ANDROID_USER_HOME/avd}"
          export ANDROID_EMULATOR_HOME="''${ANDROID_EMULATOR_HOME:-$ANDROID_USER_HOME}"
          export JAVA_HOME="${pkgs.jdk17}"
          export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
          # Gradle-демон живёт дольше контейнера и держит его mount-namespace.
          export GRADLE_OPTS="''${GRADLE_OPTS:--Dorg.gradle.daemon=false}"

          if [ "$#" -eq 0 ]; then
            cat <<'BANNER'
  onym-android — Android-окружение (SDK 36, JDK 17, эмулятор)
  Команды: onym-doctor | onym-build | onym-test | onym-lint
           onym-avd | onym-emu | onym-emu-stop | onym-ui-test | onym-install
           onym-lfs (выгрузить git-lfs ассеты)
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

        # Библиотеки, которых нет в FHS-корне: их ждут emulator/adb/aapt2 и
        # Qt/GL-стек эмулятора.
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

      # `nix run`-хелперы: каждый просто входит в контейнер и зовёт свой скрипт.
      runApp = script: {
        type = "app";
        program = "${pkgs.writeShellScript "onym-run-${script}" ''
          exec ${fhs}/bin/${fhsName} -c ${pkgs.lib.escapeShellArg script}
        ''}";
      };
    in
    {
      # Схема флейка требует уровень системы: <output>.<system>[.<name>].
      packages.x86_64-linux = {
        sdk = androidSdk.androidsdk;
        fhs = fhs;
        default = androidSdk.androidsdk;
      };

      # nix develop → сразу внутри FHS: ./gradlew и emulator работают без обёрток.
      devShells.x86_64-linux.default = fhs.env;

      apps.x86_64-linux = {
        build = runApp "onym-build";
        test = runApp "onym-test";
        lint = runApp "onym-lint";
        avd = runApp "onym-avd";
        emu = runApp "onym-emu";
        ui-test = runApp "onym-ui-test";
        doctor = runApp "onym-doctor";
        default = runApp "onym-doctor";
      };
    };
}
