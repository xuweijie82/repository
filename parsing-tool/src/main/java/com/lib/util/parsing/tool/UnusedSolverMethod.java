package com.lib.util.parsing.tool;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import com.cubs.solverlib.model.Rule;
import com.lib.util.engine.tool.service.UnusedMethodAnnotator;

public class UnusedSolverMethod {
  
  @Autowired
  private static UnusedMethodAnnotator uma;
  public static void main(String[] args) {
    final Logger log = LogManager.getLogger(UnusedSolverMethod.class);
    
    log.info("main starting spring application {} ", Arrays.toString(args));
    
    uma = new UnusedMethodAnnotator();
    
    //args[1] references the project path
    String pathOfManifestFile = uma.retrieveFilePathOfManifest(args[1]);
    
    //We read in the firing rules from Manifest first.
    Map<String, List<Rule>> firingRules = uma.readRules(pathOfManifestFile);
    
    //After which, we will read in the project code base.
    String response = uma.load(args[1], firingRules);
    
    log.info(response);
  }
}
