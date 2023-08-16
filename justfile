

stagedir := "target/universal/stage/bin"

test:
    sbt test

# Build the native executable for the frontend.
build:
    sbt stage

alias b := build

# Turn a sigi expression mentioned as argument into MLIR.
exprToMlir EXPR:
    echo "{{EXPR}}" | {{stagedir}}/sigi-to-mlir -

# Turn a sigi file mentioned as argument into MLIR.
sigiToMlir *ARGS:
    {{stagedir}}/sigi-to-mlir {{ARGS}}

# Interpret the given Sigi file.
interpretFile *ARGS:
    {{stagedir}}/interpret-sigi {{ARGS}}

# Interpret the given Sigi expression.
interpretExpr EXPR:
    echo "{{EXPR}}" | {{stagedir}}/interpret-sigi -

# Launch the repl.
repl:
    {{stagedir}}/repl



# setup commands

# Install sdkman if you haven't already
installSdkman:
    #!bin/bash
    sdkman_auto_answer=true curl -s "https://get.sdkman.io" | $SHELL

# Install required dev tools, given an existing sdkman installation
installRequirementsWithSdkman:
    #!bin/bash
    set -e
    source "$HOME/.sdkman/bin/sdkman-init.sh"
    # get scala
    sdk i scala 3.2.1
    sdk i sbt
    # get graalvm
    sdk i java 22.3.r19-grl
    # get native image
    gu install native-image


