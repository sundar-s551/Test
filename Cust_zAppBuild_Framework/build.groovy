import com.ibm.dbb.build.*
import com.ibm.dbb.build.BuildProperties
import com.ibm.jzos.Zfile

println "==> Starting the DBB Build...."

// DBB invoke the build scripts via groovyz

def props = BuildProperties.getInstance()

// Start time message
def startTime = new Date()
props.startTime = startTime.format("yyyy-MM-dd HH:mm:ss")
println "\n** Build starts at: ${props.startTime}"

def repoRoot = new File("/u/Cust_zAppBuild_Framework")

// Collect the Source files passed as CLI arguments
// Usage: groovyz build.groovy app/COBB/prog.cbl app/LNKC/test.lnk 

def sourceFiles = args.findAll { 
    it.endsWith('.cbl') || it.endsWith('.cpy') || 
    it.endsWith('.asm') || it.endsWith('.pli') || 
    it.endsWith('.lnk') || it.endsWith('.jcl') 
}
println "\n** Source files to build: ${sourceFiles}"
if(!sourceFiles) {
    println "\n** No source files to build. Exiting the build."
    System.exit(4)
}

//
//Derive application name from the first source file grandparent folder name
//Example: PENSION/COBB/prog.cbl => application name = PENSION
def firstfile = new File(sourceFiles[0]).getAbsoluteFile()
def appName = firstfile.getParentFile().getParentFile().getName().toUpperCase()
println "\n** Application name derived from the first source file: ${appName}"
// Set the application name before loading the build.properties file so that it can be used in the build.properties file
props.setProperty("APP_NAME", appName)

//
// Load Build level properties from the build.properties file
// Order matters, the properties loaded later will override the previous ones
// The build.properties file is expected to be in the build-conf folder under the repo root
props.load(new File(repoRoot, "build-conf/build.properties"))

// Dataset properties file is expected to be in the build-conf folder under the repo root
props.load(new File(repoRoot, "build-conf/dataset.properties"))

// Language Specific properties file is expected to be in the build-conf folder under the repo root
["Cobol", "Assembler", "LinkEdit", "PLI", "Copybook"].each { lang ->
    def lf = new File(repoRoot, "build-conf/language-conf/${lang}.properties")
    if(lf.exists()) {
        props.load(lf)
        println "\n** Loaded ${lang} properties from ${lf}"
    }
}

// Application level properties will override the build level properties
def appConfDir = new File(repoRoot, "/${appName}/application-conf")
if(appConfDir.exists()){
    appConfDir.eachFileMatch(~/.*\.properties/) { file ->
        props.load(file)
        println "\n** Loaded application properties from ${file}"
    }
}

// File Properties must be loaded after the build and application properties so that they can override them
def filePropsFile = new File(repoRoot, "build-conf/file.properties")
if(filePropsFile.exists()) {
    props.load(filePropsFile)
    println "\n** Loaded file properties from ${filePropsFile}"
}

// Type sequence properties ( Endevor TYPEDEQ equivalent)
def typeSeqFile = new File(repoRoot, "build-conf/typeseq.properties")
if(typeSeqFile.exists()) {
    props.load(typeSeqFile)
    println "\n** Loaded type sequence properties from ${typeSeqFile}"
}

println "==> All build properties loaded successfully. Starting the build process...."

// Create build log directory
// Pattern: <logDir>/<appName>/<yyyyMMdd_HHmmss>
def buildId = new Date().format("yyyyMMdd_HHmmss")
def buildLogDir = "${props.getProperty('logDir')}/${buildId}"
new File(buildLogDir).mkdirs()
println "\n** Build log directory created: ${buildLogDir}"

// Language Scripts mapping
//
// Key : Folder name where source files are located
// Value : Path to the build script for that language
//
// Folder-to-language mapping:
//  COBB/COBC => Cobol(Batch/online)
//  PLIB/PLIC => PLI (Batch/online)
//  ASMB/ASMC => Assembler (Batch/online)
//  LNKC/LNKB => LinkEdit (Batch/online)
//  COBCOPY/PLICOPY/ASMCOPY => Copybook (Cobol, PLI, Assembler)
def FOLDER_SCRIPT_MAP = [
    "COBB" : "languages/Cobol.groovy",
    "COBC" : "languages/Cobol.groovy",
    "PLIB" : "languages/PLI.groovy",
    "PLIC" : "languages/PLI.groovy",
    "ASMB" : "languages/Assembler.groovy",
    "ASMC" : "languages/Assembler.groovy",
    "LNKC" : "languages/LinkEdit.groovy",
    "LNKB" : "languages/LinkEdit.groovy",
    "COBCOPY" : "languages/Copybook.groovy",
    "PLICOPY" : "languages/Copybook.groovy",
    "ASMCOPY" : "languages/Copybook.groovy"
]
// Extension to folder mapping, used when folder name is not available in the source file path
// handles cases where source files are placed in a different folder than the expected one
def EXT_SCRIPT_MAP = [
    "cbl" : "languages/Cobol.groovy",
    "cpy" : "languages/Copybook.groovy",
    "asm" : "languages/Assembler.groovy",
    "pli" : "languages/PLI.groovy",
    "lnk" : "languages/LinkEdit.groovy"
]

// Load shared utilities script
def sharedLoader = this.class.classLoader
def buildUtils = new GroovyShell(sharedLoader).evaluate(new File(repoRoot, "utilities/BuildUtilities.groovy"))
def datasetUtils = new GroovyShell(sharedLoader).evaluate(new File(repoRoot, "utilities/DatasetUtilities.groovy"))

def gcl = new GroovyClassLoader(this.class.classLoader)
def stepLoggerClass = gcl.parseClass(new File(repoRoot, "utilities/StepLogger.groovy"))
def stepLogger = stepLoggerClass.newInstance()

//
// Resolve buld list and sort into Endevor type sequence order
sourceFiles = buildUtils.getBuildList(sourceFiles)
sourceFiles = buildUtils.sortBuildList(sourceFiles, props)
println "\n** Build list resolved and sorted in Endevor type sequence order: ${sourceFiles}"

//
// Process each source file 
//
int totalFiles = sourceFiles.size()
int buildCount = 0
int skippedCount = 0
int failedCount = 0

sourceFiles.each { rawPath ->
    def srcFile = new File(rawPath).getAbsoluteFile()
    def fileName = srcFile.getName()
    def folderName = srcFile.getParentFile().getName().toUpperCase()
    def ext = fileName.contains('.') ? fileName.substring(fileName.lastIndexOf('.')).toLowerCase() : ''

    println "\n** Processing source file: ${srcFile} "
    println " Folder: ${folderName}"
    println " Extension: ${ext}"

    if(!srcFile.exists()) {
        println "\n** Source file does not exist: ${srcFile}. Skipping the build for this file."
        skippedCount++
        return // Continue to the next file
    }

    // Resolve the language script based on the folder name or file extension
    // Folder map takes precedence over extension map
    def scriptRelPath = FOLDER_SCRIPT_MAP[folderName] ?: EXT_SCRIPT_MAP[ext.replace('.', '')]
    if(!scriptRelPath) {
        println "\n** No build script found for source file: ${srcFile}. Skipping the build for this file."
        skippedCount++
        return // Continue to the next file
    }
    def langScript = new File(repoRoot, scriptRelPath)
    if(!langScript.exists()) {
        println "\n** Build script does not exist: ${langScript}. Skipping the build for this file."
        skippedCount++
        return // Continue to the next file
    }

    // Build the binding for the language script:
    // source   Absolute USS path to the source file
    // buildLogDir  Absolute log directory for this build to run
    // appName  Application name derived from the first source file

    Binding langBinding = new Binding()[
        source       : srcFile.path,
        buildLogDir  : buildLogDir,
        appName      : appName,
        props        : props,
        buildUtils   : buildUtils,
        datasetUtils : datasetUtils,
        stepLogger   : stepLogger
    ]

    GroovyShell langShell = new GroovyShell(sharedLoader, langBinding)
    try {
        langShell.evaluate(langScript)
        println "\n** Build completed successfully for source file: ${srcFile}"
        buildCount++
    } catch(Exception e) {
        println "\n** Build failed for source file: ${srcFile}. Error: ${e.getMessage()}"
        failedCount++
        // Continue processing  remaining files rather than aborting the whole build
    }

}

//
//Build Summary
//
println """
** Build Summary:
   Total files: ${totalFiles}
   Successful builds: ${buildCount}
   Skipped files: ${skippedCount}
   Failed builds: ${failedCount}
"""

if(failedCount > 0) {
    println "\n** Build completed with errors. Please check the build logs in ${buildLogDir} for details."
    System.exit(8)
} else {
    println "\n** Build completed successfully. Please check the build logs in ${buildLogDir} for details."
    System.exit(0)
}

def endTime = new Date()
println "\n** ==> Build Duration: ${TimeCategory.minus(endTime, startTime)}"