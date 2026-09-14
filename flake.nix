{
  description = "TipStroke Android development shell";
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  outputs = { self, nixpkgs }:
    let system = "x86_64-linux"; pkgs = import nixpkgs { inherit system; config.android_sdk.accept_license = true; };
    in {
      devShells.${system}.default = pkgs.mkShell {
        packages = with pkgs; [ jdk17 android-tools gradle android-studio ];
        shellHook = ''
          export JAVA_HOME="${pkgs.jdk17}"
          echo "TipStroke shell: point ANDROID_HOME at your licensed SDK, then use ./gradlew."
        '';
      };
    };
}
