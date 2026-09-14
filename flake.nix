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
          echo "TipStroke shell ready. Run ./tipstroke build."
        '';
      };
    };
}
