#!/usr/bin/env node
/**
 * Copies farming and hunter timer classes from RuneLite into this project.
 * Run from repo root: cd scripts && npm install && node update.js
 * Requires RuneLite cloned as a sibling: ../runelite/
 * Based on https://github.com/queicherius/runelite-time-tracking-reminder
 */
const fs = require('fs-extra')
const path = require('path')

const RUNELITE_PLUGIN_PATH = path.join(__dirname, '../../runelite/runelite-client/src/main/java/net/runelite/client/plugins/timetracking')
const RUNELITE_COPY_PATH = path.join(__dirname, '../src/main/java/com/osrscompanion/runelite')

// Exclude UI/panel classes we don't need for OSRS Companion, plus the vendored BirdHouseTracker stack —
// OSRS Companion reads bird house state directly from the timetracking RS profile config in TimerMonitor.
const IGNORED_FILES = [
  'FarmingTabPanel.java',
  'FarmingNextTickPanel.java',
  'FarmingContractInfoBox.java',
  'FarmingContractManager.java',
  'BirdHouseTabPanel.java',
  'BirdHouseTracker.java',
  'BirdHouseData.java',
  'BirdHouseState.java',
  // The plugin's PatchImplementation and Produce read data tables; RuneLite's go to the tests (see the end).
  'PatchImplementation.java',
  'Produce.java',
]

function patchCopiedFiles(search, replace) {
  const files = getDirectoryFiles(RUNELITE_COPY_PATH)
  for (const file of files) {
    let content = fs.readFileSync(file, 'utf-8')
    const newContent = typeof search === 'string' ? content.replace(search, replace) : content.replace(search, replace)
    if (newContent !== content) {
      fs.writeFileSync(file, newContent, 'utf-8')
    }
  }
}

function getDirectoryFiles(directory) {
  if (!fs.existsSync(directory)) return []
  const entries = fs.readdirSync(directory, { withFileTypes: true })
  const files = entries.map((entry) => {
    const entryPath = path.resolve(directory, entry.name)
    if (entry.isFile() && !entry.name.endsWith('.java')) return null
    return entry.isDirectory() ? getDirectoryFiles(entryPath) : entryPath
  })
  return files.filter(Boolean).flat()
}

if (!fs.existsSync(RUNELITE_PLUGIN_PATH)) {
  console.error('RuneLite not found. Clone it as a sibling:')
  console.error('  cd .. && git clone https://github.com/runelite/runelite.git')
  console.error('  cd osrs-companion-plugin && node update.js')
  process.exit(1)
}

console.log('Creating RuneLite copy directories')
fs.mkdirpSync(path.join(RUNELITE_COPY_PATH, 'hunter'), { recursive: true })
fs.mkdirpSync(path.join(RUNELITE_COPY_PATH, 'farming'), { recursive: true })

console.log('Copying Tab.java')
fs.mkdirpSync(RUNELITE_COPY_PATH, { recursive: true })
fs.copySync(path.join(RUNELITE_PLUGIN_PATH, 'Tab.java'), path.join(RUNELITE_COPY_PATH, 'Tab.java'))

console.log('Copying farming and hunter files')
const copyOptions = {
  overwrite: true,
  filter: (src) => !IGNORED_FILES.includes(path.basename(src)),
}
fs.copySync(path.join(RUNELITE_PLUGIN_PATH, 'hunter'), path.join(RUNELITE_COPY_PATH, 'hunter'), copyOptions)
fs.copySync(path.join(RUNELITE_PLUGIN_PATH, 'farming'), path.join(RUNELITE_COPY_PATH, 'farming'), copyOptions)

console.log('Patching: Change package to com.osrscompanion.runelite')
patchCopiedFiles(
  'package net.runelite.client.plugins.timetracking.',
  'package com.osrscompanion.runelite.'
)
patchCopiedFiles(
  'package net.runelite.client.plugins.timetracking;',
  'package com.osrscompanion.runelite;'
)
patchCopiedFiles('import net.runelite.client.plugins.timetracking.Tab;', 'import com.osrscompanion.runelite.Tab;')

console.log('Patching: Remove @Inject and @Singleton')
patchCopiedFiles(/\s*@Inject\n/g, '\n')
patchCopiedFiles(/\s*@Singleton\n/g, '\n')

console.log('Patching: Make classes and constructors public')
patchCopiedFiles(/access = AccessLevel\.PACKAGE,?/g, '')
patchCopiedFiles(/access = AccessLevel\.PRIVATE,?/g, '')
patchCopiedFiles('private void updateCompletionTime', 'public void updateCompletionTime')
patchCopiedFiles('private FarmingTracker(', 'public FarmingTracker(')
patchCopiedFiles(/\bclass FarmingWorld\b/, 'public class FarmingWorld')
patchCopiedFiles(/\tFarmingWorld\(/, '\tpublic FarmingWorld(')
patchCopiedFiles(/\bclass FarmingPatch\b/, 'public class FarmingPatch')
patchCopiedFiles(/\bclass PatchPrediction\b/, 'public class PatchPrediction')
patchCopiedFiles(/\tFarmingRegion\(/, '\tpublic FarmingRegion(')

console.log('Patching: Stub UI methods (FarmingTabPanel not copied)')
patchCopiedFiles('public FarmingTabPanel createTabPanel(Tab tab, FarmingContractManager farmingContractManager)', 'public Object createTabPanel(Tab tab, Object farmingContractManager)')
patchCopiedFiles('return new FarmingTabPanel(this, compostTracker, paymentTracker, itemManager, configManager, config, farmingWorld.getTabs().get(tab), farmingContractManager);', 'return null;')

console.log('Patching: Remove config write calls (read-only for OSRS Companion)')
patchCopiedFiles(
  /configManager\.setRSProfileConfiguration\([^)]+\);/g,
  '/* config write removed - OSRS Companion is read-only */'
)

console.log('Patching: Add generation comment')
const GENERATED_COMMENT = '// Auto-generated from RuneLite. Do not edit. Run: node update.js\n\n'
patchCopiedFiles(/\/\*\s*\n\s*\* Copyright/, GENERATED_COMMENT + '/*\n * Copyright')

// Handle duplicate comment
const files = getDirectoryFiles(RUNELITE_COPY_PATH)
for (const file of files) {
  let content = fs.readFileSync(file, 'utf-8')
  content = content.replace(GENERATED_COMMENT + GENERATED_COMMENT, GENERATED_COMMENT)
  fs.writeFileSync(file, content, 'utf-8')
}

console.log('RuneLite PatchImplementation -> tests (RuneLitePatchImplementation, the patch table reference)')
const REFERENCE_PATH = path.join(__dirname, '../src/test/java/com/osrscompanion/runelite/farming/RuneLitePatchImplementation.java')
let ref = fs.readFileSync(path.join(RUNELITE_PLUGIN_PATH, 'farming', 'PatchImplementation.java'), 'utf-8').replace(/\r\n/g, '\n')
ref = ref
  .replace('package net.runelite.client.plugins.timetracking.farming;', 'package com.osrscompanion.runelite.farming;')
  .replace('import net.runelite.client.plugins.timetracking.Tab;', 'import com.osrscompanion.runelite.Tab;')
  .replace('import lombok.Getter;\n', '')
  .replace('import lombok.RequiredArgsConstructor;\n', '')
  .replace('@RequiredArgsConstructor\n@Getter\npublic enum PatchImplementation', 'public enum RuneLitePatchImplementation')
  .replace(/\tprivate final boolean healthCheckRequired;\n}\s*$/, [
    '\tprivate final boolean healthCheckRequired;',
    '',
    '\tRuneLitePatchImplementation(Tab tab, String name, boolean healthCheckRequired)',
    '\t{',
    '\t\tthis.tab = tab;',
    '\t\tthis.name = name;',
    '\t\tthis.healthCheckRequired = healthCheckRequired;',
    '\t}',
    '',
    '\tpublic Tab getTab()',
    '\t{',
    '\t\treturn tab;',
    '\t}',
    '',
    '\tpublic String getName()',
    '\t{',
    '\t\treturn name;',
    '\t}',
    '',
    '\tpublic boolean isHealthCheckRequired()',
    '\t{',
    '\t\treturn healthCheckRequired;',
    '\t}',
    '}',
    '',
  ].join('\n'))
ref = "// RuneLite's PatchImplementation, unchanged but renamed and without Lombok: the reference the plugin's\n"
  + '// patch table (patch-states.txt) is generated from and checked against. Run: node update.js\n\n' + ref
fs.mkdirpSync(path.dirname(REFERENCE_PATH))
fs.writeFileSync(REFERENCE_PATH, ref, 'utf-8')
console.log('RuneLite Produce -> tests (RuneLiteProduce, the produce table reference)')
const PRODUCE_REF_PATH = path.join(__dirname, '../src/test/java/com/osrscompanion/runelite/farming/RuneLiteProduce.java')
const fields = [['String', 'name'], ['String', 'contractName'], ['PatchImplementation', 'patchImplementation'], ['int', 'itemID'],
  ['int', 'tickrate'], ['int', 'stages'], ['int', 'regrowTickrate'], ['int', 'harvestStages']]
let produce = fs.readFileSync(path.join(RUNELITE_PLUGIN_PATH, 'farming', 'Produce.java'), 'utf-8').replace(/\r\n/g, '\n')
produce = produce
  .replace('package net.runelite.client.plugins.timetracking.farming;', 'package com.osrscompanion.runelite.farming;')
  .replace('import lombok.Getter;\n', '')
  .replace('import lombok.RequiredArgsConstructor;\n', '')
  .replace('@RequiredArgsConstructor\n@Getter\npublic enum Produce', 'public enum RuneLiteProduce')
  .replace(/\bProduce\(/g, 'RuneLiteProduce(')
  .replace(/static Produce get/g, 'static RuneLiteProduce get')
  .replace(/\(Produce produce : Produce\.values\(\)\)/g, '(RuneLiteProduce produce : RuneLiteProduce.values())')
const ctor = '\tRuneLiteProduce(' + fields.map(([t, n]) => t + ' ' + n).join(', ') + ')\n\t{\n'
  + fields.map(([, n]) => '\t\tthis.' + n + ' = ' + n + ';\n').join('') + '\t}\n\n'
const firstCtor = produce.indexOf('\tRuneLiteProduce(String name, int itemID, int tickrate, int stages, int regrowTickrate, int harvestStages)')
produce = produce.slice(0, firstCtor) + ctor + produce.slice(firstCtor)
const getters = fields.map(([t, n]) => '\tpublic ' + t + ' get' + n[0].toUpperCase() + n.slice(1) + '()\n\t{\n\t\treturn ' + n + ';\n\t}\n').join('\n')
produce = produce.slice(0, produce.lastIndexOf('}')) + getters + '}\n'
produce = "// RuneLite's Produce, unchanged but renamed and without Lombok: the reference the plugin's produce table\n"
  + '// (produce.txt) is generated from and checked against. Run: node update.js\n\n' + produce
fs.writeFileSync(PRODUCE_REF_PATH, produce, 'utf-8')

console.log('  Next: delete patch-states.txt and produce.txt in src/main/resources/com/osrscompanion/runelite/farming/,')
console.log("  then run ./gradlew test twice (the first run writes the tables from RuneLite's code, the second checks every")
console.log('  value). A new patch type or produce in RuneLite also needs its constant in PatchImplementation.java / Produce.java.')

console.log('Done. Run ./gradlew build to verify.')
