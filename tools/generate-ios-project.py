#!/usr/bin/env python3
"""Regenerate the dependency-free Xcode project; usable from Linux and macOS."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1] / "ios"
objects = {}

def key(name):
    return hashlib.sha1(name.encode()).hexdigest()[:24].upper()

def obj(label, isa, **fields):
    identifier = key(label)
    objects[identifier] = {"isa": isa, **fields}
    return identifier

app_product = obj("app-product", "PBXFileReference", explicitFileType="wrapper.application", path="TipStroke.app", sourceTree="BUILT_PRODUCTS_DIR")
unit_product = obj("unit-product", "PBXFileReference", explicitFileType="wrapper.cfbundle", path="TipStrokeTests.xctest", sourceTree="BUILT_PRODUCTS_DIR")
ui_product = obj("ui-product", "PBXFileReference", explicitFileType="wrapper.cfbundle", path="TipStrokeUITests.xctest", sourceTree="BUILT_PRODUCTS_DIR")
products = obj("products", "PBXGroup", children=[app_product, unit_product, ui_product], name="Products", sourceTree="<group>")
groups = []
sources = {}
resources = []
for folder in ["TipStroke", "TipStrokeTests", "TipStrokeUITests"]:
    children = []
    sources[folder] = []
    for source in sorted((root / folder).glob("*.swift")):
        ref = obj(f"ref:{folder}/{source.name}", "PBXFileReference", lastKnownFileType="sourcecode.swift", path=source.name, sourceTree="<group>")
        children.append(ref)
        sources[folder].append(obj(f"build:{folder}/{source.name}", "PBXBuildFile", fileRef=ref))
    if folder == "TipStroke":
        for name, kind in [("Info.plist", "text.plist.xml"), ("PrivacyInfo.xcprivacy", "text.xml"), ("Assets.xcassets", "folder.assetcatalog")]:
            ref = obj("ref:" + name, "PBXFileReference", lastKnownFileType=kind, path=name, sourceTree="<group>")
            children.append(ref)
            if name != "Info.plist":
                resources.append(obj("build:" + name, "PBXBuildFile", fileRef=ref))
    groups.append(obj("group:" + folder, "PBXGroup", children=children, path=folder, sourceTree="<group>"))
main_group = obj("main-group", "PBXGroup", children=groups + [products], sourceTree="<group>")

common = dict(IPHONEOS_DEPLOYMENT_TARGET="16.0", SDKROOT="iphoneos", SWIFT_VERSION="5.0",
              CLANG_ENABLE_MODULES="YES", CLANG_ENABLE_OBJC_ARC="YES", TARGETED_DEVICE_FAMILY="1,2",
              ENABLE_USER_SCRIPT_SANDBOXING="NO", SWIFT_STRICT_CONCURRENCY="minimal")

def configurations(name, settings):
    configs = []
    for mode in ["Debug", "Release"]:
        values = dict(settings)
        if mode == "Debug":
            values.update(SWIFT_OPTIMIZATION_LEVEL="-Onone", DEBUG_INFORMATION_FORMAT="dwarf", ENABLE_TESTABILITY="YES", SWIFT_ACTIVE_COMPILATION_CONDITIONS="DEBUG")
        else:
            values.update(SWIFT_OPTIMIZATION_LEVEL="-O", DEBUG_INFORMATION_FORMAT="dwarf-with-dsym", SWIFT_COMPILATION_MODE="wholemodule")
        configs.append(obj(name + mode, "XCBuildConfiguration", buildSettings=values, name=mode))
    return obj(name + "configs", "XCConfigurationList", buildConfigurations=configs, defaultConfigurationIsVisible=0, defaultConfigurationName="Release")

project_config = configurations("project", common)
app_settings = dict(PRODUCT_BUNDLE_IDENTIFIER="dev.tipstroke.ios", PRODUCT_NAME="$(TARGET_NAME)",
                    INFOPLIST_FILE="TipStroke/Info.plist", CODE_SIGN_STYLE="Automatic", CURRENT_PROJECT_VERSION="1",
                    MARKETING_VERSION="0.1.0", ASSETCATALOG_COMPILER_APPICON_NAME="AppIcon",
                    SUPPORTED_PLATFORMS="iphoneos iphonesimulator", SUPPORTS_MACCATALYST="NO",
                    SUPPORTS_MAC_DESIGNED_FOR_IPHONE_IPAD="NO", LD_RUNPATH_SEARCH_PATHS="$(inherited) @executable_path/Frameworks")
app_config = configurations("app", app_settings)
asset_phase = obj("prepare-assets", "PBXShellScriptBuildPhase", buildActionMask=2147483647, files=[],
                  inputPaths=["$(SRCROOT)/../app/src/main/res/drawable-nodpi/tipstroke_launcher.png"],
                  outputPaths=["$(SRCROOT)/TipStroke/Assets.xcassets/AppIcon.appiconset/AppIcon.png"],
                  name="Prepare app icon", runOnlyForDeploymentPostprocessing=0, shellPath="/bin/sh",
                  shellScript='sh "$SRCROOT/../tools/prepare-ios-assets.sh"\n')

def phases(name, include_resources=False):
    return [obj(name + "sources", "PBXSourcesBuildPhase", buildActionMask=2147483647, files=sources[name], runOnlyForDeploymentPostprocessing=0),
            obj(name + "frameworks", "PBXFrameworksBuildPhase", buildActionMask=2147483647, files=[], runOnlyForDeploymentPostprocessing=0),
            obj(name + "resources", "PBXResourcesBuildPhase", buildActionMask=2147483647, files=resources if include_resources else [], runOnlyForDeploymentPostprocessing=0)]

app = obj("app-target", "PBXNativeTarget", buildConfigurationList=app_config, buildPhases=[asset_phase] + phases("TipStroke", True), buildRules=[], dependencies=[], name="TipStroke", productName="TipStroke", productReference=app_product, productType="com.apple.product-type.application")
targets = [app]
for name, product, kind in [("TipStrokeTests", unit_product, "unit-test"), ("TipStrokeUITests", ui_product, "ui-testing")]:
    proxy = obj(name + "proxy", "PBXContainerItemProxy", containerPortal=key("project"), proxyType=1, remoteGlobalIDString=app, remoteInfo="TipStroke")
    dependency = obj(name + "dependency", "PBXTargetDependency", target=app, targetProxy=proxy)
    settings = dict(PRODUCT_BUNDLE_IDENTIFIER="dev.tipstroke.ios." + name, PRODUCT_NAME="$(TARGET_NAME)", GENERATE_INFOPLIST_FILE="YES", CODE_SIGN_STYLE="Automatic", LD_RUNPATH_SEARCH_PATHS="$(inherited) @executable_path/Frameworks @loader_path/Frameworks")
    if kind == "unit-test":
        settings.update(BUNDLE_LOADER="$(TEST_HOST)", TEST_HOST="$(BUILT_PRODUCTS_DIR)/TipStroke.app/$(BUNDLE_EXECUTABLE_FOLDER_PATH)/TipStroke")
    else:
        settings.update(TEST_TARGET_NAME="TipStroke")
    targets.append(obj(name + "target", "PBXNativeTarget", buildConfigurationList=configurations(name, settings), buildPhases=phases(name), buildRules=[], dependencies=[dependency], name=name, productName=name, productReference=product, productType="com.apple.product-type.bundle." + kind))
project = obj("project", "PBXProject", attributes={"LastUpgradeCheck": "1600", "TargetAttributes": {app: {"CreatedOnToolsVersion": "16.0"}}}, buildConfigurationList=project_config, compatibilityVersion="Xcode 14.0", developmentRegion="en", hasScannedForEncodings=0, knownRegions=["en", "Base"], mainGroup=main_group, productRefGroup=products, projectDirPath="", projectRoot="", targets=targets)

def serialize(value, level=0):
    tab = "\t" * level
    if isinstance(value, dict):
        return "{\n" + "".join("\t" * (level + 1) + json.dumps(k) + " = " + serialize(v, level + 1) + ";\n" for k, v in value.items()) + tab + "}"
    if isinstance(value, list):
        return "(" + ", ".join(serialize(v, level) for v in value) + ")"
    if isinstance(value, int):
        return str(value)
    return json.dumps(value)

payload = dict(archiveVersion=1, classes={}, objectVersion=56, objects=objects, rootObject=project)
(root / "TipStroke.xcodeproj/project.pbxproj").write_text("// !$*UTF8*$!\n" + serialize(payload) + "\n")

ref = lambda target, name: f'<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{target}" BuildableName="{name}" BlueprintName="{name.split(chr(46))[0]}" ReferencedContainer="container:TipStroke.xcodeproj"/>'
scheme = f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="1600" version="1.3">
  <BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries>
    <BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES">{ref(app, 'TipStroke.app')}</BuildActionEntry>
  </BuildActionEntries></BuildAction>
  <TestAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv="YES"><Testables>
    <TestableReference skipped="NO">{ref(targets[1], 'TipStrokeTests.xctest')}</TestableReference>
    <TestableReference skipped="NO">{ref(targets[2], 'TipStrokeUITests.xctest')}</TestableReference>
  </Testables></TestAction>
  <LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugDocumentVersioning="YES" debugServiceExtension="internal" allowLocationSimulation="YES"><BuildableProductRunnable runnableDebuggingMode="0">{ref(app, 'TipStroke.app')}</BuildableProductRunnable></LaunchAction>
  <ProfileAction buildConfiguration="Release" shouldUseLaunchSchemeArgsEnv="YES" savedToolIdentifier="" useCustomWorkingDirectory="NO" debugDocumentVersioning="YES"><BuildableProductRunnable runnableDebuggingMode="0">{ref(app, 'TipStroke.app')}</BuildableProductRunnable></ProfileAction>
  <AnalyzeAction buildConfiguration="Debug"/>
  <ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/>
</Scheme>
'''
(root / "TipStroke.xcodeproj/xcshareddata/xcschemes/TipStroke.xcscheme").write_text(scheme)
print("Generated ios/TipStroke.xcodeproj")
