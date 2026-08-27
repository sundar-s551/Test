import com.ibm.dbb.build.*
import java.io.File
import com.ibm.jzos.ZFile

/**
 * resolve ${VAR} inside a string using BuildProperties
 */
String resolveProps(String input, BuildProperties props){
    if(!input) return input

    def resolved = input

    // resolve recursively until no variables remain
    while (resolved.contains('${')){
        def matcher = (resolved =~ /\${([^}]+)\}/)


        matcher.each { match ->
            def key = match[]
            def value = props.getProperty(key)
            if(value){
                resolved = resolved.replace("\${${key}}", value)
            }
        }
    }
    return resolved
}

/*
 * Creates datasets listed in a property (comma seperated)
 * Only creates if dataset does NOT exist
 *
 * @param props         - BuildProperties
 * @param datasetList   - property key (e.g, cobol_srcDatasets)
 * @param optionskey    - property key (e.g, cobol_srcOptions)
 */

 void createDatasets(BuildProperties props, String datasetList, String optionsKey){

    def datasets = props.getProperty(datasetList)
    def options = props.getProperty(optionsKey)
    if(!datasets){
        println ">>> No datasets defined for ${datasetList}"
        return
    }
    datasets.split(",").each { dsnRaw ->
        def dsn = resolveProps(dsnRaw.trim(), props)
        if(!dsn) return

        try{
            if(ZFile.dsExists("//'${dsn}'")){
                println " Dataset Already Exists: ${dsn}"
            }else{
                println "Creating dataset: ${dsn}..."

                def alloc = new MVSExec()
                    .pgm("IEFBR14")

                alloc.dd(new DDstatement().name("OUTDD").dsn(dsn).options("new catalog ${options}")
                )

                alloc.execute()
                println " Created dataset: ${dsn}"
            }
        } catch (Exception e){
            throw new RuntimeException("! failed to create dataset ${dsn}: ${e.message}", e)
        }
    }
 }

 boolean memberExists(String pds, String member){
    try{
        def zf = new ZFile("//'${pds}(${member})'", "rb")
        zf.close()
        return true
    }catch (Exception e){
        return false
    }
 }
