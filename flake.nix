{
  description = "kiln development environment";

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
              pkgs.clj-kondo
              pkgs.cljfmt
              pkgs.clojure
              pkgs.html-tidy
              pkgs.jdk
              pkgs.jet
              pkgs.leiningen
              pkgs.marksman
              pkgs.neil
              pkgs.typescript
              pkgs.typescript-language-server
              pkgs.vscode-langservers-extracted
            ];
          };
        };
      }
    );
}
