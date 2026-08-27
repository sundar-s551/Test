/*************************************************************************************
 *  BuildUtilities.groovy
 *
 * Common utility methods shared by all language scripts
 *
 ************************************************************************************/
@groovy.transform.BaseScript com.ibm.dbb.groovy.ScriptLoader baseScript
import com.ibm.dbb.build.*
import com.ibm.dbb.build.BuildProperties
import com.ibm.jzos.ZFile
import com.ibm.dbb.metadata.*
import com.ibm.dbb.dependency.*
import groovy.transform.*
import groovy.json.JsonParserType
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.PathMatcher
import groovy.ant.*

    //
    // assertBuildProperties - verify that required build properties for a script exist
    //
    def assertBuildProperties(BuildProperties props, String requiredProps){
        if(requiredProps){
            String[] buildProps = requiredProps.aplit(',')
            buildProps.each { buildProp ->
                buildProp = buildProp.trim()
                def val = props.getProperty(buildProp)
                assert (val != null && val.trim().length() > 0 ) :
                    "*! Missing required build property '${buildProp}'"

            }
        }
    }

    // create Member Name
    //
    // Delegates to CopToPDS.createMemberName() which strips path and extension
    // uppercases, and truncates to 8 characters exactly as IBM requires
    // Always use this instead of manula .toUppercase().take(8) patterns
    def createMemberName(String buildFile){
        return CopyToPDS.createMemberName(buildFile)
    }

    // Copy source file
    // Copies a USS source file to a PDS member. Wraps CopyToPDS with a 
    // BuildException catch so failures produce a clear message
    def copySourceFile(File sourceFile, String targetPDS, String member){
        try{
            new CopyToPDS()
                .file(sourceFile)
                .dataset(targetPDS)
                .member(member)
                .execute()

        } catch (Exception e){
            throw new RuntimeException(
                "*! BuildUtilities.copySourceFile: CopyToPDS of ${sourceFile.absolutePath}" +
                " to ${targetPDS}(${member}) failed: ${e.mesaage}", e)
        
            }
        }

        //  need to implement this function for simplification of code
        // this is to verify whether dataset exists or not
        // Returns true if he MVS dataset or member exists
        // DSn must not include surroundings quotes, because this method adds them
        //
        /* static boolean dsExists(String dsn){
                try{
                    return ZFile.dsExists("'${dsn}'")
                } catch (Exception e){
                    return false
                }
            }*/


        //getBuildList
        // Accepts the raw CLI argument list, resolvs each to an absolute File
        // filters out any that do not exist ( with a warning), and returns the
        // validated list of absolute path strings ready for sorting
        List<String> getBuildList(List<String> sourceFiles){
            List<String> buildList = []
            sourceFiles.each { rawPath ->
                def f = new File(rawPath).getAbsoluteFile()
                if (f.exists()){
                    buildList << f.path
                } else{
                    println "*! File not found excluded from build: ${f.path}"
                }
            println "Build list contains ${buildList.size()} files(s)"
            return buildList

            }
        }


        //sortBuildList
        //
        //Sort the list of source file paths into correct build processing order:
        //
        //Two modes...choosen automatically based on what is loaded in progs:
        //
        // Mode 1   typeseq.properties ( Primary, fine-grained, Endevor exact match)
        //      Triggered when: at least one typeSequence.* property is present
        //      Sort key: Folder name of each file  typeSequence.<FOLDER> number
        //      exm: COBCOPY=20, COBB=140, COBC=150, LNKC=280
        //      Each folder/type is sequenced individually, exactly like Endevor TYPSEQ
        //
        // Mode 2   buildOrder in build.properties ( FALLBACK, coaese, language-level)
        //      Triggered when: on typeSequence.* properties found in props
        //      Sort Key: file extension of language script name position in the buildOrder list
        //      exm: buildOrder=Cobcopy, Assembler,Cobol,PLI,LinkEdit
        //      *.cbl  Cobol   index2
        //      All files of the same language sort together(no sub-type ordering)
        //
        // Both files(typeSequence.properties + build.properties) can coexist
        // typeSequence.properties always takes priority when present
        //
        // Files with no matching entry default to sequence 999 in both nmodes
        // and sort to the end ( same as Endevor behaviour for unknown types)
        //
        //
        // The sort is stable: files with the same sequence keep their original
        // relative order( CLI order is preserved withig a type)
        //
        List<String> sortBuildList(List<String> buildList, BuildProperties props){

            // Detect which mode to use
            boolean useTypeSeq = props.any{ key, value ->
                    key.startsWith('typeSequence.')
                }
            if(useTypeSeq){
                // Mode1: typeSequence.properties    folder   sequence number
                println "**Sort mode: typeSequence.properties(Folder-level)"

                def getSeq= { String filePath ->
                    String folder = new File(filePath).parentFile.getName().toUpperCae()
                    String seqVal = props.getProperty("typeSequence.${folder}")
                    return seqVal ? seqVal.trim().toInteger() : 999
                }

                List<String> sorted = buildList.sort(false) {a, b ->
                    getSeq(a) <=> getSeq(b)
                }

                println "** Build sequence after type-sort:"
                sorted.each{ f ->
                    String folder = new File(f).parentFile.getName().toUpperCase()
                    int  seq    = getSeq(f)
                    println "[${String.format('%3d', seq)}]   ${folder.padRight(12)}  ${new File(f).getName()}"
 
                }
                return sorted
            }else{
                //   MODE 2: buildOrder extension script name   list index
                println "* Sort Mode: buildOrder (language-level fallback)"

                def buildOrder = props.getProperty('buildOrder')
                                        ?.split(',')
                                        ?.collect{it.trim()}
                if(!buildOrder){
                    println " **Warn: Neither typeSequence.* properties not buildOrder found  build list will not be sorted"
                    return buildList
                }
                println "** buildOrder: ${buildorder}"
                
                //Map the file extension language script base name
                // Reads *.cbl = cobol.groovy style entries from language.properties
                Map<String, String> extToScript = {
                    'cbl' : 'Cobol'
                    'cob' : 'Cobol'
                    'pli' : 'PLI'
                    'pl1' : 'PLI'
                    'asm' : 'Assembler'
                    'lnk' : 'LinkEdit'
                    'cpy' : 'Cobcopy'               
                    
                }

                def getIdx = { String filePath ->
                    String ext = filePath.tokenize('.').last().toLowerCase()
                    String script = extToScript[ext]
                    int     idx = script ? buildOrder.indexOf(script) : -1
                    return idx == -1 ? 999 : idx
                }

                List<String> sorted = buildList.sort{false} {a, b ->
                    getIdx(a) <=> getIdx(b)
                }

                println "* Build sequence after buildOrder sort:"
                sorted.each { f->
                    String ext = f.tokenize('.').last().toLowerCase()
                    String script = extToScript[ext] ?: 'unknown'
                    int   idx   = getIdx(f)
                    println "[${String.format('%3d', idx)}]   ${script.padRight(12)}  ${new File(f).getName()}"
                }
                return sorted
            }
        }

        //
        //hasDB2
        //
        //Determines whether a build file contains DB2 SQl
        // Priority:
        //  1. Generic file-level property override: hadDB2 = true|false
        //  2. Source scan for EXEC SQl token
        //
        def hasDB2(BuildProperties props, String buildFile){
            // Generic Key first (our standard, checked by all script)
            /* String flag = props.getFileProperty('hasDB2', buildFile)
            if (flag != null) return flag.toBoolean()*/
            // Fall back to source scan
            boolean found = false
            new File(buildFile).eachLine { line ->

            if(!found){
                def l = line.toUpperCase().replaceAll("\\s+", " ")
                if (l.contains("EXEC SQL")) found = true

            }

            }
            return found
        }
        println " hasDB2 value with scan done"

        //
        // hasCICS
        //
        // Determines whether a build file uses CICS
        // Priority:
        //      1. Generic file-level property: hasCICS = true|false
        //      2. Source scan for EXEC CICS token
        //
        def hasCICS(BuildProperties props, String buildFile){
            String flag = props.getFileProperty('hasCICS', buildFile)
            if(flag != null) return flag.toBoolean()
            boolean found = false
            new File(buildFile).eachLine { line ->
                if(!found && line.toUpperCase().contains('EXEC CICS')) found= true
            }
            return found
        }

