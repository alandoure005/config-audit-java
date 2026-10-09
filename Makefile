JAVA ?= java
JAVAC ?= javac
JAR ?= jar
RELEASE ?= 17
BUILD := build
MAIN_SOURCES := $(shell find src/main/java -name '*.java' -print)
TEST_SOURCES := $(shell find src/test/java -name '*.java' -print)

.PHONY: all build test check demo clean help
all: check build

$(BUILD)/main.stamp: $(MAIN_SOURCES) Makefile
	@mkdir -p $(BUILD)/classes
	$(JAVAC) --release $(RELEASE) -Xlint:all -Werror -d $(BUILD)/classes $(MAIN_SOURCES)
	@touch $@

$(BUILD)/test.stamp: $(TEST_SOURCES) $(BUILD)/main.stamp
	@mkdir -p $(BUILD)/test-classes
	$(JAVAC) --release $(RELEASE) -Xlint:all -Werror -cp $(BUILD)/classes -d $(BUILD)/test-classes $(TEST_SOURCES)
	@touch $@

$(BUILD)/config-audit.jar: $(BUILD)/main.stamp
	$(JAR) --create --file $@ --main-class dev.configaudit.Main -C $(BUILD)/classes .

build: $(BUILD)/config-audit.jar

test: $(BUILD)/test.stamp
	$(JAVA) -ea -cp $(BUILD)/classes:$(BUILD)/test-classes dev.configaudit.TestSuite

check: test

demo: build
	$(JAVA) -jar $(BUILD)/config-audit.jar --schema examples/service.schema --config examples/valid.env --format env
	$(JAVA) -jar $(BUILD)/config-audit.jar --schema examples/service.schema --config examples/valid.properties --format properties --output json

clean:
	rm -rf $(BUILD)

help:
	@echo 'make check: compile strictly and run tests'
	@echo 'make build: create executable JAR'
	@echo 'make demo: audit passing env and properties examples'
	@echo 'make clean: remove generated build files'
