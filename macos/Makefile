# Brainfence macOS — common tasks. Requires Xcode 16.3+ (Swift 6.1, needed by
# the PowerSync and Supabase Swift packages) and XcodeGen (brew install xcodegen).

CONFIG ?= Debug
DERIVED := build/DerivedData

.PHONY: project build release test test-core install clean

## Generate Brainfence.xcodeproj from project.yml (re-run after adding files).
project:
	./scripts/generate-config.sh
	xcodegen generate

## Build the app (Debug by default: make build CONFIG=Release).
build: project
	xcodebuild -project Brainfence.xcodeproj -scheme Brainfence -configuration $(CONFIG) \
		-derivedDataPath $(DERIVED) -destination 'platform=macOS' build

release:
	$(MAKE) build CONFIG=Release

## Pure-logic unit tests (no Xcode project needed).
test-core:
	cd BrainfenceCore && swift test

test: test-core

## Build Release and copy it to /Applications (where the login agent can register).
install: release
	rm -rf /Applications/Brainfence.app
	cp -R $(DERIVED)/Build/Products/Release/Brainfence.app /Applications/
	open /Applications/Brainfence.app

clean:
	rm -rf build Brainfence.xcodeproj Brainfence/Generated
