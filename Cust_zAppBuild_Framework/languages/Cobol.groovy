/********************************************************************************
 * COBOL.groovy script
 * Equivalent to the Broadcom Endevor COBOL generate processor
 *
 * Details of Steps:
 *     Step1    Copy Source file from USS to PDS member
 *     Step2    DB2 Precompile ( Only when EXEC SQl statements)
 *     Step3    CICS preprocessor ( Only when EXEC CICS detected)
 *     Step4     COBOL Compile
 *     Step5    Check DBRM member Exists
 *     Step6    Generate and store Bind cards if prog is db2 related
 *     Step7    Execute the DB2 Bind
 *     Step8    Execute NEWCOPY
 ***************************************8****************************/

 @groovy.transform.BaseScript com.ibm.dbb.groovy.ScriptLoader baseScript

 import com.ibm.dbb.build.*
 import com.ibm.dbb.build.BuildProperties
 import groovy.transform.*
 import com.ibm.jzos.ZFile

 println " COBOL build started..."

 def sourcePath = binding.getVariable("source")
 def buildLogDir = binding.getVariable("buildLogDir")
 def buildUtils = binding.getVariable("buildUtils")
 def datasetUtils = binding.getVariable("datasetUtils")
 def stepLogger = binding.getVariable("stepLogger")

 //------------------------------------------------------------------
 // Check the Source file extention
 //------------------------------------------------------------------
 def sourceFile = new File(sourcePath)
 if (!sourceFile.exists()) throw new RuntimeException("*! Source file not found: ${sourcePath}")

 // Verify required build properties files

 def requiredProps = props.getProperty('cobol_requiredBuildProperties')
 if(!requiredProps) {
    throw new RuntimeException("Missing Property: cobol_requiredBuildProperties")

 }
 buildUtils.assertBuildProperties(props, requiredProps)

//----------------------------------------------------------------
 //Derive member name (Strip extension, uppercase) - BuildUtilities
 //---------------------------------------------------------------
 def member = buildUtils.createMemberName(sourcePath)
 println "Processing Cobol Program: ${member}"

 //----------------------------------------------------------------
 // Step log file( For each step seperate log files all should be under
 // one build log directory)
 //----------------------------------------------------------------
 File logPre = new File("${buildLogDir}/${member}_Precompile.log")
 File logCics = new File("${buildLogDir}/${member}_CICSPreproc.log")
 File logComp = new File("${buildLogDir}/${member}_Compile.log")
 File logBind = new File("${buildLogDir}/${member}_Bind.log")
 File logNcpy = new File("${buildLogDir}/${member}_Newcopy.log")

 //----------------------------------------------------------------
 // PDS targets
 //----------------------------------------------------------------
 def srcPDS     = props.getProperty('cobol_srcPDS')
 def objPDS     = props.getProperty('objPDS')
 def dbrmPDS    = props.getProperty('dbrmPDS')
 def bindcarDsn = props.getProperty('bindcardPDS')
 def copyPDS    = props.getProperty('cobol_cpyPDS')

 ['cobol_srcPDS','objPDS','dbrmPDS','bindcardPDS','cobol_cpyPDS'].each { key->
        if(!props.getProperty(key)){
            throw new RuntimeException("Missing required property: ${key}")
        }
 }

 //-------------------------------------------------------------------------
 // Temp dataset optons and log encoding
 //-------------------------------------------------------------------------
 def tempOpts = props.getProperty('cobol_tempOptions')
 def logEnc   = props.getProperty('logEncoding') ?: 'IBM-1047'


 //-------------------------------------------------------------------------
 // Scan source for sybsystem markers ( EXEC SQl/ EXEC CICS)
 // Check for file.properties flags  (uncomment to override scan manual assigning values
 // of hasDB2 and hasCICS in order to skip the scanning)
 // if that is the case uncomment below two lines in this code
 // hasDB2 = props.getFileProperty('cobol_isSQL', sourcePath) == 'true'
 // hasCICS = props.getFileProperty('cobol_is CICS', sourcePath) == 'true'
 // *******************
 // For scanning the source for EXEC SQL and EXEc CICS

 boolean hasDB2 = buildUtils.hasDB2(props, sourcePath)
 boolean hasCICS = buildUtils.hasCICS(props, sourcePath)
 println "   Attributes: SQL=${hasDB2}, CICS=${hasCICS}"

 //-----------------------------------------------------------------------
 //  Temp datasets passed between steps
 //  CompilerIN tracks which dataset feeds the compiler; default = source PDS
 //-----------------------------------------------------------------------
 String db2preOUT = '&&DB2PREOUT'
 String cicsout = '&&CICSOUT'
 String compilerIn = "${srcPDS}(${member})"
 def syscinDSN = "${props.getProperty('hlq')}.TMP.SYSCIN.${member}"

 println ">>>> Ensuring required datasets exists..."

 datasetUtils.createDatasets(props, "cobol_srcDatasets", "cobol_srcOptions")
 datasetUtils.createDatasets(props, "cobol_objDatasets", "cobol_objOptions")


 /*************************************************************************/
 /*   STEP1 : Copy USS soruce to PDS member (calls buildUtilities)
 /*************************************************************************/
 def copysrc = buildUtils.copySourceFile(sourceFile, srcPDS, member)

 // MVSjob: All MVSEXECs steps use temp datasets can be shared to next steps until it is ended
 MVSJob job = new MVSJob()
 job.start()

 /*************************************************************************/
 /*    STEP2 : DB2 Precompile ( Only when EXEC SQl found)
 /*************************************************************************/
 if(hasDB2){
    println ">>> STEP2: DB2 precompile Started"
    //Below try catch  delete the if any temp ds already exist
    try{
        def rc = ["tsocmd", "DELETE '${syscinDSN}'"].execute()
        rc.waitFor()
        println " Deleted Existing syscinDSN: ${syscinDSn}"   
    } catch(e){
        //Ignore Dataset may not exist
    }

    def precompileStep = new MVSEXEC()
        .pgm(props.getProperty('cobol_precompile'))
        .parm(props.getProperty('PrecompileParms'))

    precompileStep.dd(new DDstatement().name('SYSIN').dsn("${srcPDS}(${member})").options('shr'))
    precompileStep.dd(new DDstatement().name('DBRMLIB').dsn("${dbrmPDS}(${member})").options('shr'))
    precompileStep.dd(new DDstatement().name('SYSCIN').dsn(syscinDSN).options('new catalog space(5,5) cyl unit(sysda) recfm(fb,b) lrecl(80)'))
    precompileStep.dd(new DDstatement().name('SYSLIB').dsn(props.getProperty('SCSQCOBC')).options('shr'))
    precompileStep.dd(new DDstatement().name('TASKLIB').dsn(props.getproeprty('SDSNLOAD')).oprions('shr'))

    (1..5).toList().each { num ->
        precompileStep.dd(new DDstatement().name("SYSUT$num").options('new space(5,5) cyl unit(sysda)'))
    }

    int preRc = stepLogger.executeWithSysprint(precompileStep, tempOpts, logPre, logEnc)
    if(preRc > (props.getProperty('cobol_maxDB2PreRc') ?: '4') as int) {
        throw new RuntimeException("*! DB2 precompile failed RC=${preRc} for ${member}")
    }
    compilerIn = syscinDSN
 }
 else {
    println ">>>STEP2 : DB2 Precompile skipped  (NO SQL)"
 }

 /***************************************************************************/
 /* STEP3 : CICS Preprocessor (Only when EXEC CICS found)
 /***************************************************************************/
 if(hasCICS){
    println ">>> STEP3 : CICS Preprocessor step Started"

    // If DB2 ran, feed its output to CICS, otherwise feed the source PDs directly
    String cicsInput = hasDB2 ? db2preOUT : "${srcPDS}(${member})"

    def preprocessorStep = new MVSEXEC()
        .pgm(props.getProperty('cobol_preprocessor'))
        .parm(props.getProperty('CICSPreProcessorParms'))

    preprocessorStep.dd(new DDstatement().name('SYSIN').dsn(cicsInput).options('shr'))
    preprocessorStep.dd(new DDstatement().name('SYSPUNCH').dsn(cicsout).options(tempOpts).pass(true))
    preprocessorStep.dd(new DDstatement().name('TASKLIB').dsn("${props.getProperty('CICSLIB)}.SDFHLOAD").options('shr'))

    int cicsRc = stepLogger.executeWithSysprint(preprocessorStep, tempOpts, logCics, logEnc)
    if(cicsRc > ((props.getProperty('cobol_macCICSPreRC') ?: '4') as int)){
        throw new RuntimeException("*!CICS Preprocessor failed RC=${cicsRc} for ${member}")
    }
    compilerIn = cicsout
 }
 else{
    println ">>> STEP3: CICS Preprocessor step skipped (No CICS)"

 }

 /***********************************************************************************
 /* STEP4: COBOL Compile
 /**********************************************************************************/
 println ">>>STEP4 : Cobol Compile Started"
    def compileStep = new MVSEXEC()
        .pgm(props.getProperty('cobol_compiler'))
        .parm(props.getProperty('CompileParms'))

    compileStep.dd(new DDstatement().name('SYSIN').dsn(compilerIn).options('shr'))
    // SYSLIB datasets concatenation - chain with .dd() not seperate .dd() calls
    compileStep.dd(new DDstatement().name('SYSLIB').dsn(props.getProperty('cobol_cpyPDS')).options('shr'))

    if (hasCICS){
        compileStep.dd(new DDstatement().name('SYSLIB').dsn("${props.getProperty('CICSLIB)}.SDFHCOB").options('shr)'))
    }
    (1..7).toList().each { num ->
        compileStep.dd(new DDstatement().name("SYSUT$num").options("new space(5,5) cyl unit(sysda)"))
    }
    compileStep.dd(new DDstatement().name('SYSLIN').dsn("${objPDS}(${member})").options('shr'))
    compileStep.dd(new DDstatement().name('TASKLIB').dsn(props.getProperty('cobol_steplib')).options('shr'))

    int cRc = stepLogger.executeWithSysprint(compilerStep, tempOpts, logCics, logEnc)
    println ">>>Compilation done with RC = ${cRc}"
    int cobolMaxRC = (props.getProperty('cobol_maxRC') ?: '4') as int
    if(cRc > cobolMaxRC){
        throw new RuntimeException("*!Cobol compilation  failed RC=${cRc} for ${member}")
    }

/* Temp datasets not required for further steps */
job.stop()

/***************************************************************************/
/* STEP 5 : Check whether DBRM member was produced (If no Bind skip)
/***************************************************************************/
boolean dbrmExists = datasetUtils.memberExists(dbrmPDS, member)

/***************************************************************************/
/* STEP 6 : Generate and Store bind cards
/***************************************************************************/
if (dbrmExists && hasDB2){
    String bindcard = " DSN SYSTEM(${props.getProperty('db2Subsystem)})\n" +
            "BIND PACKAGE(${props.getProperty('db2Collection')})\n"        +
            "     MEMBER(${bindcardDsn}(${member}))\n     OPTHINT(${member})\n" +
            "     OWNER(${props.getProperty('db2Owner')})\n"  +
            "     QUALIFIER(${props.getProperty('db2Qualifier')})\n" +

    //Create a temp file to hold the bind card
    File tmp = File.createTempFile("bind_${member}_", ".txt")
    try{
        tmp.setText(bindcard, 'IBM-1047')
        new CopyToPDS().dataset(bindcardDsn).member(member).file(tmp).execute()
        println "    Bind Card   ${bindcardDsn}(${member})"
        } finally{
            tmp.delete()
        }
    }else if(hasDB2){
        println " DBRM not found Skipping the bind"
    }

/***************************************************************************
/*    STEP 7: Execute DB2 Bind ( Only if bind card was successfully stored)
/***************************************************************************/
boolean bindcrdExists  = false
String bindcrdMemberDSN = "${bindcardDsn}(${member})"
try{
    def zf = new ZFile("//'${bindcrdMemberDSN}'", "rb")
    zf.close()
    bindcrdExists = true
} catch (Exception e){
    bindcrExists = false
    println " Bind card not found Skipping the bind execution"
}
if(bindcrdExists && hasDB2){
    println " Bind step has started"
    def bindStep = new MVSExec()
        .pgm('IKJEFT01')
        .parm('DYNAMNBR=20')

    bindStep.dd(new DDstatement().name('TASKLIB').dsn(props.getProperty('SDSNLOAD')).options('shr'))
    bindStep.dd(new DDstatement().name('DBRMLIB').dsn("${dbrmPDS}(${member})").options('shr'))
    bindStep.dd(new DDstatement().name('SYSIN').dsn("${bindcardDsn}(${member})").options('shr'))
    bindStep.dd(new DDstatement().name('SYSTSPRT').options('new delete space(5,5) cyl unit(vio) recfm(f,b) lrecl(121) blksize(27984)'))
    bindStep.dd(new DDstatement().name('SYSUDUMP').options('new delete space(5,5) cyl unit(vio) recfm(f,b) lrecl(121) blksize(27984)'))

    int bindRc = stepLogger.executeWithSysprint(bindStep, tempOpts, logBind, logEnc)
    if bindRc > ((props.getProperty('bind_maxRC') ?: '4') as int){
        throw new RuntimeException("*! Bind step failed RC=${bindRc} for ${member}")
    }
}

/**********************************************************************************/
/* STEP 8 : CICS NEWCOPY
/**********************************************************************************/
if(hasCICS){
    println " Issuing CICS NEWCOPY for ${member}"
    String cicscmd = " CEMT SET PROG(${member}) NEWCOPY"

    def newcopyStep = new MVSExec()
        .pgm('IKJEFT01')

    newcopyStep.dd(new DDstatement().name('STEPLIB').dsn(props.getProperty('SDSNLOAD)')).options('shr'))
    newcopyStep.dd(new DDstatement().name('SYSIN').instreamData(cicscmd))
    newcopyStep.dd(new DDstatement().name('SYSTPRT').options('SYSOUT=*'))

    int newcRc = stepLogger.executeWithSysprint(newcopyStep, tempOpts, logNcpy, logEnc)
    if (newcRc > 4){
        throw new RuntimeException("*! Cics newcopy failed")
    }
}

println " COBOL build completed for ${member}"