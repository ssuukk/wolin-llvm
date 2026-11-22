# Build the C64 examples from Wolin sources.
#
#   make            build every examples/*.wtk into examples/*.prg
#   make clean      remove the built .prg files
#
# A new examples/*.wtk is picked up automatically: the program list comes
# from the wildcard below, so no edit is needed when an example is added.
#
# The compiler is built by Gradle on first use and rebuilt whenever a
# compiler source changes. The CLI needs the runtime classpath, which the
# Gradle `printClasspath` task prints.

GRADLE := ./gradlew
MAIN := pl.qus.wolin.driver.MainKt
STDLIB := stdlib/c64/kernal.wtk stdlib/c64/vic.wtk stdlib/wolin/screen.wtk
COMPILER_SRC := $(wildcard build.gradle.kts settings.gradle.kts gradle.properties) \
                $(shell find src/main -type f 2>/dev/null)
SOURCES := $(wildcard examples/*.wtk)
PROGRAMS := $(SOURCES:.wtk=.prg)

.DEFAULT_GOAL := all
.PHONY: all clean

all: $(PROGRAMS)

# Every example links the same stdlib files. The compiler sources are a
# prerequisite, so a compiler change relinks the programs.
$(PROGRAMS): examples/%.prg: examples/%.wtk $(STDLIB) $(COMPILER_SRC)
	$(GRADLE) -q compileKotlin
	java -cp "$$($(GRADLE) -q printClasspath)" $(MAIN) $(STDLIB) $< -o $@

clean:
	rm -f examples/*.prg examples/*.prg.map
