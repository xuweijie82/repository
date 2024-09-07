package com.lib.util.parsing.tool.service;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.cubs.solverlib.model.Manifest;
import com.cubs.solverlib.model.Rule;
import com.lib.util.engine.tool.constant.EngineToolConstant;

@Service
public class UnusedMethodAnnotator {

  final Logger log = LogManager.getLogger(UnusedMethodAnnotator.class);
  private Set<String> invokedMethods = new HashSet<>();
  private Set<String> getterSetterMethods = new HashSet<>();
  private Set<String> publicMaps = new HashSet<>();
  private Set<MethodDeclaration> declaredMethods = new HashSet<>();
  private Set<String> declaredMethodsIndirectlyInvoked = new HashSet<>();
  private Set<MethodDeclaration> uninvokedMethods = new HashSet<>();

  private final Set<String> whiteListMethods =
      Set.of("findParamValue", "validateConsumedTypes", "getFqcnClass", "applyCoreFacetChange");

  public String load(String project, Map<String, List<Rule>> firingRules) {
    UnusedMethodAnnotator annotator = new UnusedMethodAnnotator();
    try {
      return annotator.annotateUnusedMethods(project, firingRules);
    } catch (IOException e) {
      log.error(e.getMessage());
    }
    return null;
  }

  public String annotateUnusedMethods(String projectPath, Map<String, List<Rule>> firingRules)
      throws IOException {

    // Parse java files but filter out accordingly for declared and invoked methods
    try (Stream<Path> javaFilesStream = Files.walk(Path.of(projectPath))) {
      // Filter for regular Java files (common for both processes)
      Set<Path> javaFiles =
          javaFilesStream
              .filter(Files::isRegularFile)
              .filter(p -> p.toString().endsWith(EngineToolConstant.JAVA_EXTENSION))
              .collect(Collectors.toSet());

      // Process declared methods
      Set<Path> javaFilesForDeclaredMethods =
          javaFiles
              .stream()
              .filter(
                  p -> {
                    try {
                      return !isExcludedFile(p); // Apply exclusion filter only for declared methods
                    } catch (IOException e) {
                      log.error(e.getMessage());
                      return false;
                    }
                  })
              .collect(Collectors.toSet());
      javaFilesForDeclaredMethods.forEach(this::processFileForDeclaredMethods);

      // Process invoked methods (using the original set without exclusions)
      javaFiles.forEach(this::processFileForInvokedMethods);
    }

    log.info("Methods not invoked:");

    List<String> invokedMethodsInManifest = retrieveAndPrintFiringRules(firingRules);

    invokedMethods.addAll(declaredMethodsIndirectlyInvoked);

    invokedMethods.addAll(whiteListMethods);

    // Check if there are any methods in the manifest file
    if (!invokedMethodsInManifest.isEmpty()) {
      // -- Gather declared method names --
      // Create a set of method names from declared methods for efficient comparison
      Set<String> declaredMethodNames =
          declaredMethods
              .stream()
              .map(MethodDeclaration::getNameAsString)
              .collect(Collectors.toSet());

      // Identify methods in manifest but not declared
      // Create a set to store methods found in manifest but not in code
      Set<String> methodsInManifestButNotInCode = new HashSet<>(invokedMethodsInManifest);
      // Remove any methods present in declaredMethodNames
      methodsInManifestButNotInCode.removeAll(declaredMethodNames);

      // -- Log results --
      if (!methodsInManifestButNotInCode.isEmpty()) {
        log.info("Methods found in Manifest but not in Code:");
        // Iterate through each missing method and log its name
        methodsInManifestButNotInCode.forEach(
            methodName -> log.info("Method name: {}", methodName));
      } else {
        // Log a message if all methods are declared
        log.info("All methods from Manifest are Declared.");
      }
    }

    for (MethodDeclaration methodDeclaration : declaredMethods) {
      // annotate methods that are not invoked and they are not unit tests
      if (!invokedMethods.contains(methodDeclaration.getNameAsString())
          && !getClassNameOfMethod(methodDeclaration).contains("Test")
          && !invokedMethodsInManifest.contains(methodDeclaration.getNameAsString())) {
        annotate(methodDeclaration);
        uninvokedMethods.add(methodDeclaration);
      }
    }

    StringBuilder sb = new StringBuilder("Class name loaded:");

    uninvokedMethods.stream().forEach(method -> log.info(method.getNameAsString()));

    uninvokedMethods.forEach(
        method -> {
          sb.append(System.lineSeparator());
          sb.append(getClassNameOfMethod(method));
          sb.append(System.lineSeparator());
          sb.append(method);
          Path filePath = method.findCompilationUnit().get().getStorage().get().getPath();
          try {
            String content = readFile(filePath.toString());

            // Annotate the method and preserve formatting
            String updatedContent =
                annotateMethod(
                    content,
                    method.getNameAsString(),
                    "Deprecated()",
                    "Deprecated by XWJ Backend Tool");

            // Write the updated content to the file
            writeFile(filePath.toString(), updatedContent);
          } catch (Exception e) {
            log.error(e.getMessage());
          }
        });

    publicMaps.forEach(
        variable -> {
          sb.append(System.lineSeparator());
          sb.append(variable);
          sb.append(System.lineSeparator());
        });
    return sb.toString();
  }

  // Helper method for exclusion logic
  private boolean isExcludedFile(Path path) throws IOException {
    List<String> exclusionPatterns =
        Arrays.asList("Application.java", "extends JpaRepository<", "interface");

    return containsTheseExtensions(path, exclusionPatterns);
  }

  private boolean containsTheseExtensions(Path filePath, List<String> extensions)
      throws IOException {
    try (Stream<String> lines = Files.lines(filePath)) {
      return lines
          .flatMap(line -> extensions.stream().filter(line::contains))
          .findAny()
          .isPresent(); // Check if any line matches any pattern
    }
  }

  // This method is to filter process each file path and filter out the invoked methods
  private void processFileForInvokedMethods(Path filePath) {
    try {

      final ParserConfiguration parserConfiguration = new ParserConfiguration();

      parserConfiguration.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);

      JavaParser javaParser = new JavaParser(parserConfiguration);

      ParseResult<CompilationUnit> result = javaParser.parse(filePath);
      if (result.isSuccessful()) {
        CompilationUnit cu = result.getResult().orElse(null);
        if (cu != null) {
          MethodInvocationVisitor methodInvocationVisitor = new MethodInvocationVisitor();
          methodInvocationVisitor.visit(cu, null);
        }
      } else {
        log.error("Parsing failed for: {}", filePath);
        List<Problem> problems = result.getProblems();
        for (Problem problem : problems) {
          log.error("Error: {}", problem);
        }
      }
    } catch (IOException e) {
      log.error("Error processing file: {}", filePath);
    }
  }

  // This method is to filter process each file path and filter out the invoked methods
  private void processFileForDeclaredMethods(Path filePath) {
    try {

      final ParserConfiguration parserConfiguration = new ParserConfiguration();

      getterSetterMethods = new HashSet<>();

      parserConfiguration.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);

      JavaParser javaParser = new JavaParser(parserConfiguration);

      ParseResult<CompilationUnit> result = javaParser.parse(filePath);
      if (result.isSuccessful()) {
        CompilationUnit cu = result.getResult().orElse(null);
        if (cu != null) {
          GetterVariableDetector getterVariableDetector = new GetterVariableDetector();
          getterVariableDetector.visit(cu, null);
          MethodDeclarationVisitor methodDeclarationVisitor = new MethodDeclarationVisitor();
          methodDeclarationVisitor.visit(cu, null);
        }
      } else {
        log.error("Parsing failed for: {}", filePath);
        List<Problem> problems = result.getProblems();
        for (Problem problem : problems) {
          log.error("Error: {}", problem);
        }
      }
    } catch (IOException e) {
      log.error("Error processing file: {}", filePath);
    }
  }

  public class GetterVariableDetector extends VoidVisitorAdapter<Void> {

    private Map<String, String> declaredFields = new HashMap<>();

    @Override
    public void visit(FieldDeclaration n, Void arg) {
      super.visit(n, arg);

      if (n.getModifiers().contains(Modifier.publicModifier())
          && n.getModifiers().contains(Modifier.staticModifier())
          && n.getElementType() instanceof ClassOrInterfaceType
          && ((ClassOrInterfaceType) n.getElementType()).getName().asString().equals("Map")) {
        publicMaps.add(
            "Found public static Map variable: "
                + n.getVariables().get(0).getName()
                + " in file "
                + getClassNameOfField(n));
      }

      for (VariableDeclarator var : n.getVariables()) {

        declaredFields.put(var.getNameAsString(), var.getType().asString());
      }
    }

    @Override
    public void visit(MethodDeclaration n, Void arg) {
      super.visit(n, arg);

      String methodName = n.getNameAsString();
      if (isGetterOrSetter(methodName)) {
        String potentialFieldName = getFieldNameFromGetterSetter(methodName);
        if (declaredFields.containsKey(potentialFieldName)) {
          // Getter has a corresponding variable declared:
          String fieldType = declaredFields.get(potentialFieldName);
          // You can access additional information like field type here
          getterSetterMethods.add(methodName);
          log.info(
              "Method: {} has corresponding variable:{} (type:{})",
              methodName,
              potentialFieldName,
              fieldType);
        }
      }
    }

    private boolean isGetterOrSetter(String methodName) {
      return methodName.startsWith(EngineToolConstant.GET)
          || methodName.startsWith(EngineToolConstant.SET)
          || methodName.startsWith(EngineToolConstant.INIT)
          || methodName.startsWith(EngineToolConstant.IS);
    }

    private String getFieldNameFromGetterSetter(String methodName) {
      String fieldName = "";
      
      if (methodName.startsWith(EngineToolConstant.GET) && methodName.length() > 3) {
        fieldName = methodName.substring(3);
      } else if (methodName.startsWith(EngineToolConstant.SET) && methodName.length() > 3) {
        fieldName = methodName.substring(3);
      } else if (methodName.startsWith(EngineToolConstant.INIT) && methodName.length() > 4) {
        fieldName = methodName.substring(4);
      } else if (methodName.startsWith(EngineToolConstant.IS) && methodName.length() > 2) {
        fieldName = methodName.substring(2);
      } else {
        fieldName = methodName; // No change if prefix not found
      }

      return fieldName.substring(0, 1).toLowerCase()
          + fieldName.substring(1); // Lowercase first letter
    }
  }

  // This method is to filter all methods within the project that is declared
  private class MethodDeclarationVisitor extends VoidVisitorAdapter<Void> {
    @Override
    public void visit(MethodDeclaration methodDeclaration, Void arg) {
      super.visit(methodDeclaration, arg);

      if (!isMethodReturningSelfForDesignPatternImplementation(methodDeclaration)
          && !getterSetterMethods.contains(methodDeclaration.getNameAsString())) {
        // to white list a method that is invoked via spring annotations
        if (indirectMethodInvokedThruAnnotation(methodDeclaration)) {
          declaredMethodsIndirectlyInvoked.add(methodDeclaration.getNameAsString());
        }
        declaredMethods.add(methodDeclaration);
      }
    }
    
    private boolean isMethodReturningSelfForDesignPatternImplementation(
        MethodDeclaration methodDeclaration) {
      String returnType = methodDeclaration.getType().toString();
      String className = getClassNameOfMethod(methodDeclaration);

      // Check if return type matches class name
      return returnType.equals(className);
    }
  }

  // This method is to filter all methods within the project that is invoked
  private class MethodInvocationVisitor extends VoidVisitorAdapter<Void> {
    @Override
    public void visit(MethodCallExpr methodCallExpr, Void arg) {
      super.visit(methodCallExpr, arg);

      invokedMethods.add(methodCallExpr.getNameAsString());
      if (methodCallExpr.getScope().isPresent()) {
        methodCallExpr.getScope().get().accept(this, arg); // Visit the scope (expression)
      }
    }

    @Override
    public void visit(ExpressionStmt expressionStmt, Void arg) {
      super.visit(expressionStmt, arg);
      expressionStmt.getExpression().accept(this, arg); // Visit the expression
    }

    @Override
    public void visit(IfStmt ifStmt, Void arg) {
      super.visit(ifStmt, arg);
      ifStmt.getThenStmt().accept(this, arg);
      ifStmt.getCondition().accept(this, arg);
      if (ifStmt.getElseStmt().isPresent()) {
        ifStmt.getElseStmt().get().accept(this, arg); // Visit the scope (expression)
      }
    }

    @Override
    public void visit(LambdaExpr lambdaExpr, Void arg) {
      // Handle lambda expressions
      super.visit(lambdaExpr, arg);
      lambdaExpr.getBody().accept(this, arg);
    }

    @Override
    public void visit(ForEachStmt forEachStmt, Void arg) {
      // Handle lambda expressions
      super.visit(forEachStmt, arg);
      forEachStmt.getBody().accept(this, arg);
    }

    @Override
    public void visit(MethodReferenceExpr methodReferenceExpr, Void arg) {
      // Handle method references
      super.visit(methodReferenceExpr, arg);
      invokedMethods.add(methodReferenceExpr.getIdentifier());
    }
  }

  // This method is to annotate unused methods with the annotation 'Deprecated'
  public void annotate(MethodDeclaration md) {
    md.addAnnotation(EngineToolConstant.DEPRECATE);
    md.setComment(new LineComment(EngineToolConstant.COMMENT));
  }

  // This method is to white list methods that are invoked by spring annotations
  private static boolean indirectMethodInvokedThruAnnotation(MethodDeclaration method) {
    // Create a whitelist of allowed annotations
    Set<String> allowedAnnotations = new HashSet<>();
    allowedAnnotations.add("Bean");
    allowedAnnotations.add("Override");
    allowedAnnotations.add("PostConstruct");
    allowedAnnotations.add("Before");
    allowedAnnotations.add("After");
    allowedAnnotations.add("Around");
    allowedAnnotations.add("RequestMapping");
    allowedAnnotations.add("GetMapping");
    allowedAnnotations.add("PostMapping");
    allowedAnnotations.add("PutMapping");
    allowedAnnotations.add("PatchMapping");
    allowedAnnotations.add("DeleteMapping");
    allowedAnnotations.add("BeforeEach");
    allowedAnnotations.add("Named");
    allowedAnnotations.add("PreDestroy");
    allowedAnnotations.add("Deprecated");
    allowedAnnotations.add("Value");
    allowedAnnotations.add("Scheduled");
    allowedAnnotations.add("JsonIgnore");
    allowedAnnotations.add("Query");
    allowedAnnotations.add("Setup");

    // Add more annotations to the whitelist as needed

    for (AnnotationExpr annotation : method.getAnnotations()) {
      String annotationName = annotation.getNameAsString();

      for (String allowedAnnotation : allowedAnnotations) {
        if (annotationName.contains(allowedAnnotation)) {
          return true;
        }
      }
    }
    return false;
  }

  // This method is to get the class name of the method
  private String getClassNameOfMethod(MethodDeclaration method) {
    // Get the parent node
    Optional<Node> parent = method.getParentNode();

    // Check if the parent is a ClassOrInterfaceDeclaration
    if (parent.get() instanceof ClassOrInterfaceDeclaration) {
      ClassOrInterfaceDeclaration classDecl = (ClassOrInterfaceDeclaration) parent.get();

      // Get the class name as a string
      String className = classDecl.getNameAsString();

      // Use the className as needed
      log.info("Method {} resides in class: {}", method.getNameAsString(), className);
      return className;
    } else {
      // Handle the case where the enclosing type is not a class or interface
      log.info("Method declared in a non class");
      return "Method declared in a non class";
    }
  }

  private String getClassNameOfField(FieldDeclaration field) {
    // Get the parent node
    Optional<Node> parent = field.getParentNode();

    // Check if the parent is a ClassOrInterfaceDeclaration
    if (parent.get() instanceof ClassOrInterfaceDeclaration) {
      ClassOrInterfaceDeclaration classDecl = (ClassOrInterfaceDeclaration) parent.get();

      // Get the class name as a string
      String className = classDecl.getNameAsString();

      // Use the className as needed
      log.info("Field {} resides in class: {}", field.getVariable(0).getNameAsString(), className);
      return className;
    } else {
      // Handle the case where the enclosing type is not a class or interface
      log.info("Field declared in a non class");
      return "Field declared in a non class";
    }
  }

  private static String readFile(String filePath) throws IOException {
    return new String(Files.readAllBytes(Paths.get(filePath)));
  }

  private static String annotateMethod(
      String content, String methodName, String annotationName, String comment) {
    StringBuilder updatedContent = new StringBuilder();
    String[] lines = content.split(EngineToolConstant.NEW_LINE);

    // Get the current timestamp
    Date now = new Date();

    // Create a SimpleDateFormat object with the desired format
    SimpleDateFormat formatter = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");

    // Format the timestamp and print it
    String formattedTimestamp = formatter.format(now);

    // Process each line and add annotation
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];

      // Add the original line content
      updatedContent.append(line);

      // Add a newline character
      updatedContent.append(EngineToolConstant.NEW_LINE);

      // Check for the target method line and add annotation (if found and has not been deprecated
      // before)
      if (i + 1 < lines.length
          && lines[i + 1].contains(" " + methodName + "(")
          && !lines[i].contains(EngineToolConstant.DEPRECATED)) {
        int indent = countIndent(lines[i + 1]);
        updatedContent
            .append(EngineToolConstant.SPACE.repeat(indent))
            .append("//")
            .append(comment)
            .append(EngineToolConstant.NEW_LINE);
        updatedContent
            .append(EngineToolConstant.SPACE.repeat(indent))
            .append("//")
            .append("Deprecated on " + formattedTimestamp)
            .append(EngineToolConstant.NEW_LINE);
        updatedContent
            .append(EngineToolConstant.SPACE.repeat(indent))
            .append(EngineToolConstant.AT)
            .append(annotationName)
            .append(EngineToolConstant.NEW_LINE);
      }
    }

    return updatedContent.toString();
  }

  private static int countIndent(String line) {
    int indent = 0;
    for (char c : line.toCharArray()) {
      if (c == ' ' || c == '\t') {
        indent++;
      } else {
        break;
      }
    }
    return indent;
  }

  private static void writeFile(String filePath, String content) throws IOException {
    Files.write(Paths.get(filePath), content.getBytes());
  }

  public Map<String, List<Rule>> readRules(String filename) {
    Map<String, List<Rule>> firingRules = new HashMap<>();
    var mapper = new ObjectMapper();

    try {
      File file = new File(filename);

      var rulesStream = new FileInputStream(file);

      var manifest = mapper.readValue(rulesStream, Manifest.class);

      rulesStream.close();
      for (var rule : manifest.getRules()) {
        for (var element : rule.getMethods()) {
          if (firingRules.containsKey(element.getName())) {
            firingRules.get(element.getName()).add(rule);
          } else {
            List<Rule> rules = new ArrayList<>();
            rules.add(rule);
            firingRules.put(element.getName(), rules);
          }
        }
      }

      retrieveAndPrintFiringRules(firingRules);

    } catch (Exception ex) {
      log.error(ex.getMessage());
    }
    return firingRules;
  }

  private List<String> retrieveAndPrintFiringRules(Map<String, List<Rule>> firingRules) {

    List<String> listOfInvokedMethodsInManifest = new ArrayList<>();
    firingRules
        .entrySet()
        .stream()
        // Flatten rule categories into a single stream of rules
        .flatMap(entry -> entry.getValue().stream())
        // Extract methods for each rule and process directly
        .map(
            rule ->
                rule.getMethods()
                    .stream()
                    .peek(method -> listOfInvokedMethodsInManifest.add(method.getName()))
                    .peek(
                        method ->
                            log.info("Rule: {}, Method: {}", rule.getName(), method.getName()))
                    .collect(Collectors.toList()))
        // Combine rule method lists for potential further processing
        .collect(Collectors.toList());

    return listOfInvokedMethodsInManifest;
  }

  public String retrieveFilePathOfManifest(String projectPath) {
    try (Stream<Path> fileStream = Files.walk(Path.of(projectPath))) {
      Optional<Path> manifestPath =
          fileStream
              .filter(Files::isRegularFile)
              .filter(
                  p ->
                      p.getFileName().toString().equalsIgnoreCase("manifest.json")
                          && !p.toString().contains("bin"))
              .findFirst();

      if (manifestPath.isPresent()) {
        // Manifest found, use the absolute path
        Path pathOfManifest = manifestPath.get().toAbsolutePath();
        log.info("Absolute path of manifest.json: {}", pathOfManifest.toAbsolutePath());
        return manifestPath.get().toAbsolutePath().toString();

      } else {
        // Manifest not found, handle the situation
        log.warn("manifest.json not found in the project.");
      }
    } catch (IOException e) {
      log.error("Error while searching for manifest.json: {}", e.getMessage());
    }
    return "";
  }
}
