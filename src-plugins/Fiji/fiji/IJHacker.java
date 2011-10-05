package fiji;

/**
 * Modify some IJ1 quirks at runtime, thanks to Javassist
 */

import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtField;
import javassist.CtMethod;
import javassist.Modifier;
import javassist.NotFoundException;

import javassist.expr.ConstructorCall;
import javassist.expr.ExprEditor;
import javassist.expr.FieldAccess;
import javassist.expr.Handler;
import javassist.expr.MethodCall;
import javassist.expr.NewExpr;

public class IJHacker implements Runnable {
	public final static String appName = "Fiji";

	protected String replaceAppName = ".replace(\"ImageJ\", \"" + appName + "\")";

	public void run() {
		try {
			ClassPool pool = ClassPool.getDefault();
			CtClass clazz;
			CtMethod method;
			CtField field;

			// Class ij.IJ
			clazz = pool.get("ij.IJ");

			// tell runUserPlugIn() to mention which class was not found if a dependency is missing
			method = clazz.getMethod("runUserPlugIn",
				"(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)Ljava/lang/Object;");
			method.instrument(new ExprEditor() {
				@Override
				public void edit(Handler handler) throws CannotCompileException {
					try {
						if (handler.getType().getName().equals("java.lang.NoClassDefFoundError"))
							handler.insertBefore("String cause = $1.getMessage();"
							+ "int index = cause.indexOf('(') + 1;"
							+ "int endIndex = cause.indexOf(')', index);"
							+ "if (!suppressPluginNotFoundError && index > 0 && endIndex > index) {"
							+ "  String name = cause.substring(index, endIndex);"
							+ "  error(\"Did not find required class: \" + $1.getMessage());"
							+ "  return null;"
							+ "}");
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			});
			// tell the error() method to use "Fiji" as window title
			method = clazz.getMethod("error",
				"(Ljava/lang/String;Ljava/lang/String;)V");
			method.insertBefore("if ($1 == null || $1.equals(\"ImageJ\")) $1 = \"" + appName + "\";");

			clazz.toClass();

			// Class ij.ImageJ
			clazz = pool.get("ij.ImageJ");

			// tell the superclass java.awt.Frame that the window title is "Fiji"
			for (CtConstructor ctor : clazz.getConstructors())
				ctor.instrument(new ExprEditor() {
					@Override
					public void edit(ConstructorCall call) throws CannotCompileException {
						if (call.getMethodName().equals("super"))
							call.replace("super(\"" + appName + "\");");
					}
				});
			// tell the version() method to prefix the version with "Fiji/"
			method = clazz.getMethod("version", "()Ljava/lang/String;");
			method.insertAfter("$_ = \"" + appName + "/\" + $_;");
			// tell the run() method to use "Fiji" instead of "ImageJ" in the Quit dialog
			method = clazz.getMethod("run", "()V");
			replaceAppNameInNew(method, "ij.gui.GenericDialog", 1, 2);
			replaceAppNameInCall(method, "addMessage", 1, 1);

			clazz.toClass();

			// Class ij.Prefs
			clazz = pool.get("ij.Prefs");

			// use Fiji instead of ImageJ
			clazz.getField("vistaHint").setName("originalVistaHint");
			field = new CtField(pool.get("java.lang.String"), "vistaHint", clazz);
			field.setModifiers(Modifier.STATIC | Modifier.PUBLIC | Modifier.FINAL);
			clazz.addField(field, "originalVistaHint" + replaceAppName + ";");

			clazz.toClass();

			// Class ij.gui.YesNoCancelDialog
			clazz = pool.get("ij.gui.YesNoCancelDialog");

			// use Fiji as window title in the Yes/No dialog
			for (CtConstructor ctor : clazz.getConstructors())
				ctor.instrument(new ExprEditor() {
					@Override
					public void edit(ConstructorCall call) throws CannotCompileException {
						if (call.getMethodName().equals("super"))
							call.replace("super($1, \"ImageJ\".equals($2) ? \"" + appName + "\" : $2, $3);");
					}
				});

			clazz.toClass();

			// Class ij.gui.Toolbar
			clazz = pool.get("ij.gui.Toolbar");

			// use Fiji/ImageJ in the status line
			method = clazz.getMethod("showMessage", "(I)V");
			method.instrument(new ExprEditor() {
				@Override
				public void edit(MethodCall call) throws CannotCompileException {
					if (call.getMethodName().equals("showStatus"))
						call.replace("if ($1.startsWith(\"ImageJ \")) $1 = \"" + appName + "/\" + $1;"
							+ "ij.IJ.showStatus($1);");
				}
			});

			clazz.toClass();

			// Class ij.plugin.CommandFinder
			clazz = pool.get("ij.plugin.CommandFinder");

			// use Fiji in the window title
			method = clazz.getMethod("export", "()V");
			replaceAppNameInNew(method, "ij.text.TextWindow", 1, 5);

			clazz.toClass();

			// Class ij.plugin.Hotkeys
			clazz = pool.get("ij.plugin.Hotkeys");

			// Replace application name in removeHotkey()
			method = clazz.getMethod("removeHotkey", "()V");
			replaceAppNameInCall(method, "addMessage", 1, 1);
			replaceAppNameInCall(method, "showStatus", 1, 1);

			clazz.toClass();

			// Class ij.plugin.Options
			clazz = pool.get("ij.plugin.Options");

			// Replace application name in restart message
			method = clazz.getMethod("appearance", "()V");
			replaceAppNameInCall(method, "showMessage", 2, 2);

			clazz.toClass();

			// Class JavaScriptEvaluator
			clazz = pool.get("JavaScriptEvaluator");

			// make sure Rhino gets the correct class loader
			method = clazz.getMethod("run", "()V");
			method.insertBefore("Thread.currentThread().setContextClassLoader(ij.IJ.getClassLoader());");

			clazz.toClass();
		} catch (NotFoundException e) {
			e.printStackTrace();
		} catch (CannotCompileException e) {
			System.err.println(e.getMessage() + "\n" + e.getReason());
			e.printStackTrace();
			Throwable cause = e.getCause();
			if (cause != null)
				cause.printStackTrace();
		}
	}

	/**
	 * Replace the application name in the given method in the given parameter to the given constructor call
	 */
	public void replaceAppNameInNew(final CtMethod method, final String name, final int parameter, final int parameterCount) throws CannotCompileException {
		final String replace = getReplacement(parameter, parameterCount);
		method.instrument(new ExprEditor() {
			@Override
			public void edit(NewExpr expr) throws CannotCompileException {
				if (expr.getClassName().equals(name))
					expr.replace("$_ = new " + name + replace + ";");
			}
		});
	}

	/**
	 * Replace the application name in the given method in the given parameter to the given method call
	 */
	public void replaceAppNameInCall(final CtMethod method, final String name, final int parameter, final int parameterCount) throws CannotCompileException {
		final String replace = getReplacement(parameter, parameterCount);
		method.instrument(new ExprEditor() {
			@Override
			public void edit(MethodCall call) throws CannotCompileException {
				if (call.getMethodName().equals(name))
					call.replace("$0." + name + replace + ";");
			}
		});
	}

	private String getReplacement(int parameter, int parameterCount) {
		final StringBuilder builder = new StringBuilder();
		builder.append("(");
		for (int i = 1; i <= parameterCount; i++) {
			if (i > 1)
				builder.append(", ");
			builder.append("$").append(i);
			if (i == parameter)
				builder.append(replaceAppName);
		}
		builder.append(")");
		return builder.toString();
	}
}