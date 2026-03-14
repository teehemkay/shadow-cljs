{
  description = "Clojure development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    systems.url = "github:nix-systems/default-darwin";
    flake-utils.url = "github:numtide/flake-utils";

    # Override flake-utils systems to mine
    flake-utils.inputs.systems.follows = "systems";
  };

  outputs =
    {
      nixpkgs,
      systems,
      flake-utils,
      ...
    }:
    let
      eachSystem =
        f: flake-utils.lib.eachSystem (import systems) (system: f nixpkgs.legacyPackages.${system});

    in
    eachSystem (
      pkgs:
      let
        inherit (pkgs) mkShell;

      in
      {
        devShells = {
          default = mkShell {
            packages = [
              pkgs.babashka
              pkgs.bun
              pkgs.clj-kondo
              pkgs.clojure
              pkgs.jdk
            ];
          };
        };
      }
    );
}
