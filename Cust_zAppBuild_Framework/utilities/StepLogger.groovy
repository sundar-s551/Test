import com.ibm.dbb.build.*
import java.io.File

// Its a custom utility to execute dbb steps and store their logs into uss files
//It basically executes build step(MVSExec/JOBExec)
// Captures the return code for the step
// Writes the execution result into a log file is uSS
// Stores the log in the build directory created by main buildscript

class StepLogger {
    int executeWithSysprint(MVSExec step, String tempOptions, File logFile, String encoding="IBM-1047"){
        // Use temp dataset ( No manual allocation needed)
        //String sysprintDD = "&&SYSPRINT"

        step.dd(new DDstatement()
            .name("SYSPRINT")
            .options("new delete space(5,5) cyl unit(vio) recfm(F,B) lrecl(121) blksize(27984)"))

        // Copy Sysprint to USS log files
        if(logFile != null){
            step.copy(new CopyToHFS()
                .ddName("SYSPRINT")
                .file(logFile)
                .hfsEncoding(encoding)
                .append(false)
            )
        }
        return step.execute()
    }
}