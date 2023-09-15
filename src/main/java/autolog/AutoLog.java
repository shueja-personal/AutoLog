package autolog;

import edu.wpi.first.util.sendable.Sendable;
import edu.wpi.first.wpilibj.DriverStation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

public class AutoLog {

  public static final NTLogger ntLogger = new NTLogger();
  public static final DataLogger dataLogger = new DataLogger();

  private static String camelToNormal(String camelCase) {
    StringBuilder sb = new StringBuilder();
    for (char c : camelCase.toCharArray()) {
      if (Character.isUpperCase(c)) {
        sb.append(' ');
      }
      sb.append(c);
    }
    sb.setCharAt(0, Character.toUpperCase(sb.charAt(0)));
    return sb.toString();
  }

  private static String methodNameFix(String name) {
    if (name.startsWith("get")) {
      name = name.substring(3);
    } else if (name.endsWith("getter")) {
      name = name.substring(0, name.length() - 6);
    }
    name = name.substring(0, 1).toLowerCase() + name.substring(1);
    return name;
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.FIELD, ElementType.METHOD})
  public @interface BothLog {
    public String path() default "";

    public boolean once() default false;
  }

  /**
   * Annotate a field or method IN A SUBSYSTEM with this to log it to SmartDashboard
   *
   * <p>Supported Types(primitive or not): Double, Boolean, String, Integer, <br>
   * Double[], Boolean[], String[], Integer[], Sendable
   *
   * @param oneShot [optional] whether or not to only log once
   */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.FIELD, ElementType.METHOD})
  public @interface DataLog {
    public String path() default "";

    public boolean once() default false;
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.FIELD, ElementType.METHOD})
  public @interface NTLog {
    public String path() default "";

    public boolean once() default false;
  }

  private static Supplier<?> getSupplier(Field field, Logged loggable) {
    field.setAccessible(true);
    return () -> {
      try {
        return field.get(loggable);
      } catch (IllegalArgumentException | IllegalAccessException e) {
        DriverStation.reportWarning(field.getName() + " supllier is erroring", false);
        e.printStackTrace();
        return null;
      }
    };
  }

  private static Supplier<?> getSupplier(Method method, Logged loggable) {
    return () -> {
      try {
        return method.invoke(loggable);
      } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
        DriverStation.reportWarning(method.getName() + " supllier is erroring", false);
        return null;
      }
    };
  }

  public static void setupLogging(Logged loggable, String rootPath, boolean createDataLog) {
    System.out.println(rootPath);
    String ss_name = rootPath;
    for (Field field : getInheritedPrivateFields(loggable.getClass())) {
      
      field.setAccessible(true);
      if (isNull(field, loggable)) {

        continue;
      }
      if (Logged.class.isAssignableFrom(field.getType())) {
        System.out.println("found logged:" + field.getName());
        try {
          String pathOverride = ((Logged) field.get(loggable)).getPath();
          if (pathOverride.equals("")) {
            pathOverride = field.getName();
          } 
          AutoLog.setupLogging(
              (Logged) field.get(loggable), ss_name + "/" + pathOverride, createDataLog);
          continue;
        } catch (IllegalArgumentException | IllegalAccessException e) {
          DriverStation.reportWarning(field.getName() + " supllier is erroring", false);
          e.printStackTrace();
          continue;
        }
      }

      if (field.getType().isArray()) {
        try {
          // Skip if primitive array
          if (!Object.class.isAssignableFrom(field.get(loggable).getClass().getComponentType())) {
            continue;
          }
          // Include all elements whose runtime class is Loggable
          for (Object obj : (Object[]) field.get(loggable)) {
            if (obj instanceof Logged) {
              try {
                String pathOverride = ((Logged) obj).getPath();
                if (pathOverride.equals("")) {
                  pathOverride = obj.getClass().getSimpleName();
                } 
                AutoLog.setupLogging(
                    (Logged) obj,
                    ss_name + "/" + field.getName() + "/" + pathOverride,
                    createDataLog);
                continue;
              } catch (IllegalArgumentException e) {
                DriverStation.reportWarning(field.getName() + " supllier is erroring", false);
                e.printStackTrace();
              }
            }
          }
        } catch (IllegalAccessException e) {
          e.printStackTrace();
        }
        // Proceed on all valid elements
        // Handle collections similarly
      } else if (Collection.class.isAssignableFrom(field.getType())) {
        try {
          int idx = 0;
          // Include all elements whose runtime class is Loggable
          for (Object obj : (Collection) field.get(loggable)) {
            System.out.println(obj);
            if (obj instanceof Logged) {
              try {
                String pathOverride = ((Logged) obj).getPath();
                if (pathOverride.equals("")) {
                  pathOverride = obj.getClass().getSimpleName() + "[" + idx++ + "]";
                } 
                AutoLog.setupLogging(
                    (Logged) obj,
                    ss_name + "/" + field.getName() + "/" + pathOverride,
                    createDataLog);
                continue;
              } catch (IllegalArgumentException e) {
                e.printStackTrace();
                DriverStation.reportWarning(field.getName() + " supllier is erroring", true);
              }
            }
          }
        } catch (IllegalAccessException e) {
          e.printStackTrace();
        }
      }
      if (field.getAnnotations().length == 0) {
        continue;
      }
      // setup the annotation.
      String annotationPath;
      boolean oneShot;
      String name = field.getName();
      DataType type;
      try{
      type = DataType.fromClass(field.getType());
      } catch (IllegalArgumentException e) {
        continue;
      }
      if ((field.isAnnotationPresent(DataLog.class) || field.isAnnotationPresent(BothLog.class))
          && createDataLog) {
        dataLogger.startLog();

        DataLog annotation = field.getAnnotation(DataLog.class);
        if (annotation == null) {
          BothLog logAnnotation = field.getAnnotation(BothLog.class);
          annotationPath = logAnnotation.path();
          oneShot = logAnnotation.once();
        } else {
          annotationPath = annotation.path();
          oneShot = annotation.once();
        }
        String key = annotationPath.equals("") ? ss_name + "/" + name : annotationPath;
        if (type == DataType.Sendable) {
          dataLogger.addSendable(key, (Sendable) getSupplier(field, loggable).get());
        } else {
          dataLogger.helper(getSupplier(field, loggable), type, key, oneShot);
        }
      }
      if (field.isAnnotationPresent(NTLog.class) || field.isAnnotationPresent(BothLog.class)) {

        NTLog annotation = field.getAnnotation(NTLog.class);
        if (annotation == null) {
          BothLog logAnnotation = field.getAnnotation(BothLog.class);
          annotationPath = logAnnotation.path();
          oneShot = logAnnotation.once();
        } else {
          annotationPath = annotation.path();
          oneShot = annotation.once();
        }
        String key = annotationPath.equals("") ? ss_name + "/" + field.getName() : annotationPath;
        if (type == DataType.Sendable) {
          ntLogger.addSendable(key, (Sendable) getSupplier(field, loggable).get());
        } else {
          ntLogger.helper(getSupplier(field, loggable), type, key, oneShot);
        }
      }
    }

    for (Method method :getInheritedMethods(loggable.getClass())) {
      if ((method.isAnnotationPresent(DataLog.class) || method.isAnnotationPresent(BothLog.class))
          && createDataLog) {
        dataLogger.startLog();
        method.setAccessible(true);
        String annotationPath;
        boolean oneShot;

        DataLog annotation = method.getAnnotation(DataLog.class);
        if (annotation == null) {
          BothLog logAnnotation = method.getAnnotation(BothLog.class);
          annotationPath = logAnnotation.path();
          oneShot = logAnnotation.once();
        } else {
          annotationPath = annotation.path();
          oneShot = annotation.once();
        }
        String name = method.getName(); // methodNameFix(method.getName());
        String path = annotationPath.equals("") ? ss_name + "/" + name : annotationPath;
        DataType type = DataType.fromClass(method.getReturnType());
        if (method.getParameterCount() > 0) {
          throw new IllegalArgumentException("Cannot have parameters on a DataLog method");
        }
        dataLogger.helper(getSupplier(method, loggable), type, path, oneShot);
      }
      if (method.isAnnotationPresent(NTLog.class) || method.isAnnotationPresent(BothLog.class)) {
        method.setAccessible(true);
        String annotationPath;
        boolean oneShot;

        NTLog annotation = method.getAnnotation(NTLog.class);
        if (annotation == null) {
          BothLog logAnnotation = method.getAnnotation(BothLog.class);
          annotationPath = logAnnotation.path();
          oneShot = logAnnotation.once();
        } else {
          annotationPath = annotation.path();
          oneShot = annotation.once();
        }
        String key = annotationPath.equals("") ? ss_name + "/" + method.getName() : annotationPath;
        DataType type = DataType.fromClass(method.getReturnType());
        if (method.getParameterCount() > 0) {
          throw new IllegalArgumentException("Cannot have parameters on a DataLog method");
        }
        ntLogger.helper(getSupplier(method, loggable), type, key, oneShot);
      }
    }
  }

  public static void update() {
    ntLogger.update();
    dataLogger.update();
  }

  public static void updateNT() {
    ntLogger.update();
  }

  public static void updateDataLog() {
    dataLogger.update();
  }

  private static boolean isNull(Field field, Object obj) {
    field.setAccessible(true);
    boolean isNull = true;
    try {
      isNull = field.get(obj) == null;
    } catch (IllegalAccessException e) {
      e.printStackTrace();
    }
    return isNull;
  }

  private static List<Field> getInheritedPrivateFields(Class<?> type) {
    List<Field> result = new ArrayList<Field>();

    Class<?> i = type;
    while (i != null && i != Object.class) {
        Collections.addAll(result, i.getDeclaredFields());
        i = i.getSuperclass();
    }

    return result;
}
  private static List<Method> getInheritedMethods(Class<?> type) {
    List<Method> result = new ArrayList<Method>();

    Class<?> i = type;
    while (i != null && i != Object.class) {
        Collections.addAll(result, i.getDeclaredMethods());
        i = i.getSuperclass();
    }

    return result;
  }
}
