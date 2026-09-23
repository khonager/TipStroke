{
  description = "TipStroke Android development shell";
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  outputs = { self, nixpkgs }:
    let system = "x86_64-linux"; pkgs = import nixpkgs { inherit system; };
    in {
      devShells.${system}.default = pkgs.mkShell {
        # Android Studio and Gradle are intentionally omitted: the former is
        # unfree in nixpkgs and the repository already carries a Gradle wrapper.
        packages = with pkgs; [ jdk17 ];
        shellHook = ''
          export JAVA_HOME="${pkgs.jdk17}"
          export TIPSTROKE_NIX_SHELL=1
          export LD_LIBRARY_PATH="${pkgs.lib.makeLibraryPath (with pkgs; [
            libx11 libxcomposite libxcursor libxdamage libxext libxfixes libxi
            libxrandr libxrender libxtst libxcb libxkbcommon libxkbfile libsm libice
            libGL libdrm fontconfig freetype libpng expat libbsd libuuid pulseaudio
            alsa-lib dbus glib gtk3 nss nspr zlib
          ])}''${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
          export QT_XKB_CONFIG_ROOT="${pkgs.xkeyboard_config}/share/X11/xkb"
          echo "TipStroke shell ready. Run ./tipstroke build."
        '';
      };
    };
}
