package lombok.javac.handlers;

import static lombok.javac.Javac.*;
import static lombok.javac.handlers.JavacHandlerUtil.*;

import java.util.Collection;

import lombok.AccessLevel;
import lombok.core.AST.Kind;
import lombok.core.AnnotationValues;
import lombok.core.TransformationsUtil;
import lombok.javac.Javac;
import lombok.javac.JavacAnnotationHandler;
import lombok.javac.JavacNode;
import lombok.javac.JavacTreeMaker;
import lombok.javac.handlers.JavacHandlerUtil.CopyJavadoc;
import lombok.javac.handlers.JavacHandlerUtil.FieldAccess;
import lombok.javac.handlers.JavacHandlerUtil.MemberExistsResult;

import org.mangosdk.spi.ProviderFor;

import com.doctusoft.ObservableProperty;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCAssign;
import com.sun.tools.javac.tree.JCTree.JCBinary;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.JCTree.JCIf;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.JCTree.JCReturn;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCTypeParameter;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;

@ProviderFor(JavacAnnotationHandler.class)
public class HandleObservableProperty extends JavacAnnotationHandler<ObservableProperty> {

	@Override public void handle(AnnotationValues<ObservableProperty> annotation, JCAnnotation ast, JavacNode annotationNode) {
		Collection<JavacNode> fields = annotationNode.upFromAnnotationToFields();
		AccessLevel level = AccessLevel.PUBLIC;
		List<JCAnnotation> onMethod = unboxAndRemoveAnnotationParameter(ast, "onMethod", "@Setter(onMethod=", annotationNode);
		List<JCAnnotation> onParam = unboxAndRemoveAnnotationParameter(ast, "onParam", "@Setter(onParam=", annotationNode);
		createSetterForFields(level, fields, annotationNode, true, onMethod, onParam);
	}

	public void createSetterForFields(AccessLevel level, Collection<JavacNode> fieldNodes, JavacNode errorNode, boolean whineIfExists, List<JCAnnotation> onMethod, List<JCAnnotation> onParam) {
		for (JavacNode fieldNode : fieldNodes) {
			boolean isModelObject = false;
			// TODO this should be more intelligent, without false positives of course, and checking supertypes as well ... (if possible at all)
			JCClassDecl classDecl = (JCClassDecl) fieldNode.up().get();
			if (classDecl != null && classDecl.implementing != null) {
				for (JCExpression implementing : classDecl.implementing) {
					// JCIdent
					if (implementing.toString().contains("ModelObject")) {
						isModelObject = true;
						break;
					}
				}
			}
			createSetterForField(level, fieldNode, errorNode, whineIfExists, onMethod, onParam, isModelObject);
			new HandleGetter().generateGetterForField(fieldNode, errorNode.get(), level, false);
		}
	}
	
	public void createSetterForField(AccessLevel level, JavacNode fieldNode, JavacNode source, boolean whineIfExists, List<JCAnnotation> onMethod, List<JCAnnotation> onParam, boolean isModelObject) {
		if (fieldNode.getKind() != Kind.FIELD) {
			fieldNode.addError("@Setter is only supported on a class or a field.");
			return;
		}
		
		JCVariableDecl fieldDecl = (JCVariableDecl)fieldNode.get();
		String methodName = toSetterName(fieldNode);
		
		if (methodName == null) {
			source.addWarning("Not generating setter for this field: It does not fit your @Accessors prefix list.");
			return;
		}
		
		if ((fieldDecl.mods.flags & Flags.FINAL) != 0) {
			source.addWarning("Not generating setter for this field: Setters cannot be generated for final fields.");
			return;
		}
		
		for (String altName : toAllSetterNames(fieldNode)) {
			switch (methodExists(altName, fieldNode, false, 1)) {
			case EXISTS_BY_LOMBOK:
			case EXISTS_BY_USER: {
				String altNameExpl = "";
				if (!altName.equals(methodName)) altNameExpl = String.format(" (%s)", altName);
				source.addWarning(
					String.format("Not generating %s(): A method with that name already exists%s. Automatic change propagation won't work!", methodName, altNameExpl));
				return;
			}
			default:
			case NOT_EXISTS:
				//continue scanning the other alt names.
			}
		}
		
		long access = toJavacModifier(level) | (fieldDecl.mods.flags & Flags.STATIC);
		
		JCMethodDecl createdSetter = createSetter(access, fieldNode, fieldNode.getTreeMaker(), source.get(), onMethod, onParam);
		injectMethod(fieldNode.up(), createdSetter);
		JCVariableDecl listenersField = createListenersField(access, fieldNode, fieldNode.getTreeMaker(), source.get(), onMethod, onParam);
		injectField(fieldNode.up(), listenersField);
		if (JavacHandlerUtil.fieldExists("$$listeners", fieldNode) == MemberExistsResult.NOT_EXISTS) {
			JCVariableDecl beanListenersField = createBeanListenersField(access, fieldNode, fieldNode.getTreeMaker(), source.get(), onMethod, onParam);
			injectField(fieldNode.up(), beanListenersField);
		}
		if (isModelObject) {
			if (methodExists("getModelObjectDescriptor", fieldNode, 0) == MemberExistsResult.NOT_EXISTS) {
				JCMethodDecl methodDecl = createGetModelObjectDescriptor(access, fieldNode, fieldNode.getTreeMaker(), source.get());
				injectMethod(fieldNode.up(), methodDecl);
			}
		}
	}
	
	public static JCMethodDecl createSetter(long access, JavacNode field, JavacTreeMaker treeMaker, JCTree source, List<JCAnnotation> onMethod, List<JCAnnotation> onParam) {
		String setterName = toSetterName(field);
		boolean returnThis = shouldReturnThis(field);
		return createSetter(access, field, treeMaker, setterName, returnThis, source, onMethod, onParam);
	}
	
	public static JCMethodDecl createGetModelObjectDescriptor(long access, JavacNode field, JavacTreeMaker treeMaker, JCTree source) {
		
		ListBuffer<JCStatement> statements = new ListBuffer<JCStatement>();
		
		Name methodName = field.toName("getModelObjectDescriptor");
		
		JCReturn result = treeMaker.Return(treeMaker.Select(treeMaker.Ident(field.toName(field.up().getName() + "_")), field.toName("_descriptor")));
		statements.add(result);
		
		JCExpression methodType = chainDotsString(field.up(), "com.doctusoft.bean.ModelObjectDescriptor");
		
		JCBlock methodBody = treeMaker.Block(0, statements.toList());
		List<JCTypeParameter> methodGenericParams = List.nil();
		List<JCVariableDecl> parameters = List.nil();
		List<JCExpression> throwsClauses = List.nil();
		
		JCMethodDecl decl = recursiveSetGeneratedBy(treeMaker.MethodDef(treeMaker.Modifiers(access), methodName, methodType,
				methodGenericParams, parameters, throwsClauses, methodBody, null), source, field.getContext());
		copyJavadoc(field, decl, CopyJavadoc.SETTER);
		return decl;
	}

	
	public static JCVariableDecl createListenersField(long access, JavacNode field, JavacTreeMaker maker, JCTree source, List<JCAnnotation> onMethod, List<JCAnnotation> onParam) {
		JCVariableDecl fieldDecl = (JCVariableDecl) field.get();
		JCExpression typeRef = chainDotsString(field.up(), "com.doctusoft.bean.internal.PropertyListeners");
		// field initialization removed. The PropertyListeners is now instantiated lazyly when first adding a listener
		JCVariableDecl listenersField = recursiveSetGeneratedBy(maker.VarDef(
				maker.Modifiers(Flags.PUBLIC),
				field.toName("$$" + fieldDecl.name.toString() + "$listeners"), typeRef,
					null), source, field.up().getContext());

		return listenersField;
	}

	public static JCVariableDecl createBeanListenersField(long access, JavacNode field, JavacTreeMaker maker, JCTree source, List<JCAnnotation> onMethod, List<JCAnnotation> onParam) {
		JCExpression typeRef = chainDotsString(field.up(), "com.doctusoft.bean.internal.BeanPropertyListeners");
		// field initialization removed. The PropertyListeners is now instantiated lazyly when first adding a listener
		JCVariableDecl listenersField = recursiveSetGeneratedBy(maker.VarDef(
				maker.Modifiers(Flags.PUBLIC),
				field.toName("$$listeners"), typeRef,
					null), source, field.up().getContext());

		return listenersField;
	}
	
	private static final List<JCExpression> NIL_EXPRESSION = List.nil();

	public static JCMethodDecl createSetter(long access, JavacNode field, JavacTreeMaker treeMaker, String setterName, boolean shouldReturnThis, JCTree source, List<JCAnnotation> onMethod, List<JCAnnotation> onParam) {
		if (setterName == null) return null;
		
		JCVariableDecl fieldDecl = (JCVariableDecl) field.get();
		
		JCExpression fieldRef = createFieldAccessor(treeMaker, field, FieldAccess.ALWAYS_FIELD);
		JCAssign assign = treeMaker.Assign(fieldRef, treeMaker.Ident(fieldDecl.name));
		
		ListBuffer<JCStatement> statements = new ListBuffer<JCStatement>();
		List<JCAnnotation> nonNulls = findAnnotations(field, TransformationsUtil.NON_NULL_PATTERN);
		List<JCAnnotation> nullables = findAnnotations(field, TransformationsUtil.NULLABLE_PATTERN);
		
		Name methodName = field.toName(setterName);
		List<JCAnnotation> annsOnParam = copyAnnotations(onParam).appendList(nonNulls).appendList(nullables);
		
		long flags = JavacHandlerUtil.addFinalIfNeeded(Flags.PARAMETER, field.getContext());
		JCVariableDecl param = treeMaker.VarDef(treeMaker.Modifiers(flags, annsOnParam), fieldDecl.name, fieldDecl.vartype, null);
		
		if (nonNulls.isEmpty()) {
			statements.append(treeMaker.Exec(assign));
		} else {
			JCStatement nullCheck = generateNullCheck(treeMaker, field);
			if (nullCheck != null) statements.append(nullCheck);
			statements.append(treeMaker.Exec(assign));
		}
		
		JCExpression methodType = null;
		if (shouldReturnThis) {
			methodType = cloneSelfType(field);
		}
		
		if (methodType == null) {
			//WARNING: Do not use field.getSymbolTable().voidType - that field has gone through non-backwards compatible API changes within javac1.6.
			methodType = treeMaker.Type(Javac.createVoidType(treeMaker, CTC_VOID));
			shouldReturnThis = false;
		}
		
		if (shouldReturnThis) {
			JCReturn returnStatement = treeMaker.Return(treeMaker.Ident(field.toName("this")));
			statements.append(returnStatement);
		}
		
		{
			// invoke value change listeners
			JCIdent listenersField = treeMaker.Ident(field.toName("$$" + fieldDecl.name.toString() + "$listeners"));
			JCBinary condition = treeMaker.Binary(CTC_NOT_EQUAL, listenersField, treeMaker.Literal(CTC_BOT, null));
			List<JCExpression> listenerArgs = List.of((JCExpression) treeMaker.Ident(fieldDecl.name));
			JCMethodInvocation listenerInvocation = treeMaker.Apply(NIL_EXPRESSION, treeMaker.Select(listenersField, field.toName("fireListeners")), listenerArgs);
			JCIf listenerIf = treeMaker.If(condition, treeMaker.Exec(listenerInvocation), null);
			statements.append(listenerIf);
		}
		{
			// invoke bean value change listeners
			JCIdent listenersField = treeMaker.Ident(field.toName("$$listeners"));
			JCBinary condition = treeMaker.Binary(CTC_NOT_EQUAL, listenersField, treeMaker.Literal(CTC_BOT, null));
			List<JCExpression> listenerArgs = List.of(
							treeMaker.Ident(field.toName("this")),
							treeMaker.Select(treeMaker.Ident(field.toName(field.up().getName() + "_")), field.toName("_" + field.getName())),
							(JCExpression) treeMaker.Ident(fieldDecl.name));
			JCMethodInvocation listenerInvocation = treeMaker.Apply(NIL_EXPRESSION, treeMaker.Select(listenersField, field.toName("fireListeners")), listenerArgs);
			JCIf listenerIf = treeMaker.If(condition, treeMaker.Exec(listenerInvocation), null);
			statements.append(listenerIf);
		}
		
		JCBlock methodBody = treeMaker.Block(0, statements.toList());
		List<JCTypeParameter> methodGenericParams = List.nil();
		List<JCVariableDecl> parameters = List.of(param);
		List<JCExpression> throwsClauses = List.nil();
		JCExpression annotationMethodDefaultValue = null;
		
		List<JCAnnotation> annsOnMethod = copyAnnotations(onMethod);
		if (isFieldDeprecated(field)) {
			annsOnMethod = annsOnMethod.prepend(treeMaker.Annotation(genJavaLangTypeRef(field, "Deprecated"), List.<JCExpression>nil()));
		}
		
		JCMethodDecl decl = recursiveSetGeneratedBy(treeMaker.MethodDef(treeMaker.Modifiers(access, annsOnMethod), methodName, methodType,
				methodGenericParams, parameters, throwsClauses, methodBody, annotationMethodDefaultValue), source, field.getContext());
		copyJavadoc(field, decl, CopyJavadoc.SETTER);
		return decl;
	}
}
