package lombok.eclipse.handlers;

import static lombok.eclipse.Eclipse.*;
import static lombok.eclipse.handlers.EclipseHandlerUtil.*;

import java.io.StringWriter;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import lombok.AccessLevel;
import lombok.core.AST.Kind;
import lombok.core.AnnotationValues;
import lombok.core.TransformationsUtil;
import lombok.eclipse.Eclipse;
import lombok.eclipse.EclipseAnnotationHandler;
import lombok.eclipse.EclipseNode;
import lombok.eclipse.handlers.EclipseHandlerUtil.FieldAccess;
import lombok.eclipse.handlers.EclipseHandlerUtil.MemberExistsResult;

import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.jdt.internal.compiler.ast.Annotation;
import org.eclipse.jdt.internal.compiler.ast.Argument;
import org.eclipse.jdt.internal.compiler.ast.Assignment;
import org.eclipse.jdt.internal.compiler.ast.EqualExpression;
import org.eclipse.jdt.internal.compiler.ast.Expression;
import org.eclipse.jdt.internal.compiler.ast.FieldDeclaration;
import org.eclipse.jdt.internal.compiler.ast.FieldReference;
import org.eclipse.jdt.internal.compiler.ast.IfStatement;
import org.eclipse.jdt.internal.compiler.ast.MessageSend;
import org.eclipse.jdt.internal.compiler.ast.MethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.NameReference;
import org.eclipse.jdt.internal.compiler.ast.NullLiteral;
import org.eclipse.jdt.internal.compiler.ast.OperatorIds;
import org.eclipse.jdt.internal.compiler.ast.QualifiedTypeReference;
import org.eclipse.jdt.internal.compiler.ast.ReturnStatement;
import org.eclipse.jdt.internal.compiler.ast.SingleNameReference;
import org.eclipse.jdt.internal.compiler.ast.Statement;
import org.eclipse.jdt.internal.compiler.ast.ThisReference;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.ast.TypeReference;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.lookup.TypeIds;
import org.mangosdk.spi.ProviderFor;

import com.doctusoft.ObservableProperty;

@ProviderFor(EclipseAnnotationHandler.class)
public class HandleObservableProperty extends EclipseAnnotationHandler<ObservableProperty> {
	
	private FieldDeclaration fieldDeclaration;
	
	public boolean generateForType(EclipseNode typeNode, Annotation ast, EclipseNode annotationNode) {
		TypeDeclaration typeDecl = null;
		if (typeNode.get() instanceof TypeDeclaration) typeDecl = (TypeDeclaration) typeNode.get();
		int modifiers = typeDecl == null ? 0 : typeDecl.modifiers;
		boolean notAClass = (modifiers &
				(ClassFileConstants.AccInterface | ClassFileConstants.AccAnnotation)) != 0;
		
		if (typeDecl == null || notAClass) {
			annotationNode.addError("@Property and @ObservableProperty is only supported on a class, an enum, or a field.");
			return false;
		}
		
		for (EclipseNode field : typeNode.down()) {
			if (field.getKind() == Kind.FIELD) {
				handlePropertyForField(field, ast, annotationNode);
			}
		}
		return true;
	}
	

	public void handlePropertyForField(EclipseNode fieldNode, Annotation ast, EclipseNode annotationNode) {
		List<Annotation> onMethod = unboxAndRemoveAnnotationParameter(ast, "onMethod", "@Setter(onMethod=", annotationNode);
		List<Annotation> onParam = unboxAndRemoveAnnotationParameter(ast, "onParam", "@Setter(onParam=", annotationNode);
		createSetterForField(AccessLevel.PUBLIC, fieldNode, annotationNode, annotationNode.get(), true, onMethod, onParam);
		new HandleGetter().createGetterForField(AccessLevel.PUBLIC, fieldNode, annotationNode, annotationNode.get(), true, false, onMethod);
	}
	
	public void createGetModelObjectDescriptorIfNeeded(EclipseNode typeNode, ASTNode source) {
		TypeDeclaration typeDeclarationNode = (TypeDeclaration) typeNode.get();
		boolean isModelObject = false;
		if (typeDeclarationNode != null && typeDeclarationNode.superInterfaces != null) {
			for (TypeReference tr : typeDeclarationNode.superInterfaces) {
				// TODO this should be more intelligent, without false positives of course, and checking supertypes as well ... (if possible at all)
				if (getTypeName(tr.getTypeName()).contains("ModelObject")) {
					isModelObject = true;
					break;
				}
			}
		}
		if (isModelObject) {
			if (methodExists("getModelObjectDescriptor", typeNode, true, 0) == MemberExistsResult.NOT_EXISTS) {
				MethodDeclaration getModelObjectDescriptor = createGetModelObjectDescriptor(typeDeclarationNode, typeNode, source);
				injectMethod(typeNode, getModelObjectDescriptor);
			}
		}
	}
	
	@Override public void handle(AnnotationValues<ObservableProperty> annotation, Annotation ast, EclipseNode annotationNode) {
		for (EclipseNode fieldNode : annotationNode.upFromAnnotationToFields()) {
			handlePropertyForField(fieldNode, ast, annotationNode);
			createGetModelObjectDescriptorIfNeeded(fieldNode.up(), annotationNode.get());
		}
		if (annotationNode.up().getKind() == Kind.TYPE) {
			generateForType(annotationNode.up(), ast, annotationNode);
			createGetModelObjectDescriptorIfNeeded(annotationNode.up(), annotationNode.get());
		}
	}
	
	public void createSetterForField(
			AccessLevel level, EclipseNode fieldNode, EclipseNode errorNode,
			ASTNode source, boolean whineIfExists, List<Annotation> onMethod,
			List<Annotation> onParam) {
		
		if (fieldNode.getKind() != Kind.FIELD) {
			errorNode.addError("@Setter is only supported on a class or a field.");
			return;
		}
		
		FieldDeclaration field = (FieldDeclaration) fieldNode.get();
		TypeReference fieldType = copyType(field.type, source);
		boolean isBoolean = isBoolean(fieldType);
		String setterName = toSetterName(fieldNode, isBoolean);
		boolean shouldReturnThis = shouldReturnThis(fieldNode);
		
		if (setterName == null) {
			errorNode.addWarning("Not generating setter for this field: It does not fit your @Accessors prefix list.");
			return;
		}
		
		int modifier = toEclipseModifier(level) | (field.modifiers & ClassFileConstants.AccStatic);
		
		for (String altName : toAllSetterNames(fieldNode, isBoolean)) {
			switch (methodExists(altName, fieldNode, false, 1)) {
			case EXISTS_BY_LOMBOK:
			case EXISTS_BY_USER: {
				String altNameExpl = "";
				if (!altName.equals(setterName)) altNameExpl = String.format(" (%s)", altName);
				errorNode.addError(
						String.format("Not generating %s(): A method with that name already exists%s. Automatic change propagation won't work!", setterName, altNameExpl));
				return;
			}
			default:
			case NOT_EXISTS:
				//continue scanning the other alt names.
			}
		}
		
		MethodDeclaration method = createSetter((TypeDeclaration) fieldNode.up().get(), fieldNode, setterName, shouldReturnThis, modifier, source, onMethod, onParam);
		injectMethod(fieldNode.up(), method);
		String listenersFieldName = "$$" + new String(field.name) + "$listeners";
		if (EclipseHandlerUtil.fieldExists(listenersFieldName, fieldNode) == MemberExistsResult.NOT_EXISTS) {
			FieldDeclaration listenersField = createListenersField((TypeDeclaration) fieldNode.up().get(), fieldNode, setterName, shouldReturnThis, modifier, source, listenersFieldName);
			injectField(fieldNode.up(), listenersField);
		}
		String beanListenersFieldName = "$$listeners";
		if (EclipseHandlerUtil.fieldExists("$$listeners", fieldNode) == MemberExistsResult.NOT_EXISTS) {
			FieldDeclaration beanListenerField = createBeanListenersField((TypeDeclaration) fieldNode.up().get(), fieldNode, setterName, shouldReturnThis, modifier, source, beanListenersFieldName);
			injectField(fieldNode.up(), beanListenerField);
		}
	}
	
	static MethodDeclaration createGetModelObjectDescriptor(TypeDeclaration parent, EclipseNode typeNode, ASTNode source) {
		int pS = source.sourceStart, pE = source.sourceEnd;
		long p = (long)pS << 32 | pE;

		MethodDeclaration method = new MethodDeclaration(parent.compilationResult);
		method.modifiers = ClassFileConstants.AccPublic;
		method.returnType = createTypeReference("com.doctusoft.bean.ModelObjectDescriptor", source);
		method.returnType.sourceStart = pS; method.returnType.sourceEnd = pE;
		method.annotations = null;
		method.arguments = null;
		method.selector = "getModelObjectDescriptor".toCharArray();
		method.binding = null;
		method.thrownExceptions = null;
		method.typeParameters = null;
		method.bits |= ECLIPSE_DO_NOT_TOUCH_FLAG;
		method.bodyStart = method.declarationSourceStart = method.sourceStart = source.sourceStart;
		method.bodyEnd = method.declarationSourceEnd = method.sourceEnd = source.sourceEnd;
		
		
		FieldReference propertyField = new FieldReference("descriptor".toCharArray(), p);
		propertyField.receiver = new SingleNameReference((typeNode.getName() + "_").toCharArray(), p);
		ReturnStatement returnStatement = new ReturnStatement(propertyField, pS, pE);
		
		method.statements = new Statement [] { returnStatement };

		method.traverse(new SetGeneratedByVisitor(source), parent.scope);
		
		return method;
	}

	static FieldDeclaration createListenersField(TypeDeclaration parent, EclipseNode fieldNode, String name, boolean shouldReturnThis, int modifier, ASTNode source, String fieldName) {
		FieldDeclaration listenerDeclaration = new FieldDeclaration(fieldName.toCharArray(), 0,0);
		listenerDeclaration.type = createTypeReference("com.doctusoft.bean.internal.PropertyListeners", source );
		listenerDeclaration.bits |= Eclipse.ECLIPSE_DO_NOT_TOUCH_FLAG;
		listenerDeclaration.modifiers |= ClassFileConstants.AccPublic;
		// field initialization removed. The PropertyListeners is now instantiated lazyly when first adding a listener
		return listenerDeclaration;
	}
	
	static FieldDeclaration createBeanListenersField(TypeDeclaration parent, EclipseNode fieldNode, String name, boolean shouldReturnThis, int modifier, ASTNode source, String fieldName) {
		FieldDeclaration listenerDeclaration = new FieldDeclaration(fieldName.toCharArray(), 0,0);
		listenerDeclaration.type = createTypeReference("com.doctusoft.bean.internal.BeanPropertyListeners", source );
		listenerDeclaration.bits |= Eclipse.ECLIPSE_DO_NOT_TOUCH_FLAG;
		listenerDeclaration.modifiers |= ClassFileConstants.AccPublic;
		// field initialization removed. The PropertyListeners is now instantiated lazyly when first adding a listener
		return listenerDeclaration;
	}
	
	public static TypeReference createTypeReference(String typeName, ASTNode source) {
		int pS = source.sourceStart, pE = source.sourceEnd;
		long p = (long)pS << 32 | pE;
		
		TypeReference typeReference;
		if (typeName.contains(".")) {
			
			char[][] typeNameTokens = fromQualifiedName(typeName);
			long[] pos = new long[typeNameTokens.length];
			Arrays.fill(pos, p);
			
			typeReference = new QualifiedTypeReference(typeNameTokens, pos);
		}
		else {
			typeReference = null;
		}
		
		setGeneratedBy(typeReference, source);
		return typeReference;
	}
	
	public static String getTypeName(char [][] typeName) {
		StringWriter sw = new StringWriter();
		for (int i = 0; i < typeName.length; i ++) {
			String part = new String(typeName[i]);
			if (i > 0)
				sw.append('.');
			sw.append(part);
		}
		return sw.toString();
	}

	static MethodDeclaration createSetter(TypeDeclaration parent, EclipseNode fieldNode, String name, boolean shouldReturnThis, int modifier, ASTNode source, List<Annotation> onMethod, List<Annotation> onParam) {
		FieldDeclaration field = (FieldDeclaration) fieldNode.get();
		int pS = source.sourceStart, pE = source.sourceEnd;
		long p = (long)pS << 32 | pE;
		MethodDeclaration method = new MethodDeclaration(parent.compilationResult);
		method.modifiers = modifier;
		if (shouldReturnThis) {
			method.returnType = cloneSelfType(fieldNode, source);
		}
		
		if (method.returnType == null) {
			method.returnType = TypeReference.baseTypeReference(TypeIds.T_void, 0);
			method.returnType.sourceStart = pS; method.returnType.sourceEnd = pE;
			shouldReturnThis = false;
		}
		Annotation[] deprecated = null;
		if (isFieldDeprecated(fieldNode)) {
			deprecated = new Annotation[] { generateDeprecatedAnnotation(source) };
		}
		Annotation[] copiedAnnotations = copyAnnotations(source, onMethod.toArray(new Annotation[0]), deprecated);
		if (copiedAnnotations.length != 0) {
			method.annotations = copiedAnnotations;
		}
		Argument param = new Argument(field.name, p, copyType(field.type, source), Modifier.FINAL);
		param.sourceStart = pS; param.sourceEnd = pE;
		method.arguments = new Argument[] { param };
		method.selector = name.toCharArray();
		method.binding = null;
		method.thrownExceptions = null;
		method.typeParameters = null;
		method.bits |= ECLIPSE_DO_NOT_TOUCH_FLAG;
		Expression fieldRef = createFieldAccessor(fieldNode, FieldAccess.ALWAYS_FIELD, source);
		NameReference fieldNameRef = new SingleNameReference(field.name, p);
		Assignment assignment = new Assignment(fieldRef, fieldNameRef, (int)p);
		assignment.sourceStart = pS; assignment.sourceEnd = assignment.statementEnd = pE;
		method.bodyStart = method.declarationSourceStart = method.sourceStart = source.sourceStart;
		method.bodyEnd = method.declarationSourceEnd = method.sourceEnd = source.sourceEnd;
		
		Annotation[] nonNulls = findAnnotations(field, TransformationsUtil.NON_NULL_PATTERN);
		Annotation[] nullables = findAnnotations(field, TransformationsUtil.NULLABLE_PATTERN);
		List<Statement> statements = new ArrayList<Statement>(5);
		if (nonNulls.length == 0) {
			statements.add(assignment);
		} else {
			Statement nullCheck = generateNullCheck(field, source);
			if (nullCheck != null) statements.add(nullCheck);
			statements.add(assignment);
		}
		
		// invoke value change listeners
		//   if (propertyListeners != null)
		{
			Expression condition = new EqualExpression(new SingleNameReference(("$$" + new String(field.name) + "$listeners").toCharArray(), p),
						new NullLiteral(pS, pE), OperatorIds.NOT_EQUAL);
			MessageSend invoke = new MessageSend();
			invoke.selector = "fireListeners".toCharArray();
			invoke.receiver = new SingleNameReference(("$$" + new String(field.name) + "$listeners").toCharArray(), p);
			invoke.arguments = new Expression[1];
			invoke.arguments[0] = new SingleNameReference(field.name, p);
			IfStatement ifStatement = new IfStatement(condition, invoke, pS, pE);
			statements.add(ifStatement);
		}
		// invoke bean change listeners
		{
			Expression condition = new EqualExpression(new SingleNameReference(("$$listeners").toCharArray(), p),
						new NullLiteral(pS, pE), OperatorIds.NOT_EQUAL);
			FieldReference propertyField = new FieldReference(("_" + new String(field.name)).toCharArray(), p);
			propertyField.receiver = new SingleNameReference((fieldNode.up().getName() + "_").toCharArray(), p);
			MessageSend invoke = new MessageSend();
			invoke.selector = "fireListeners".toCharArray();
			invoke.receiver = new SingleNameReference(("$$listeners").toCharArray(), p);
			invoke.arguments = new Expression[3];
			invoke.arguments[0] = new ThisReference(pS, pE);
			invoke.arguments[1] = propertyField;
			invoke.arguments[2] = new SingleNameReference(field.name, p);
			IfStatement ifStatement = new IfStatement(condition, invoke, pS, pE);
			statements.add(ifStatement);
		}
		
		
		if (shouldReturnThis) {
			ThisReference thisRef = new ThisReference(pS, pE);
			ReturnStatement returnThis = new ReturnStatement(thisRef, pS, pE);
			statements.add(returnThis);
		}
		method.statements = statements.toArray(new Statement[0]);
		
		Annotation[] copiedAnnotationsParam = copyAnnotations(source, nonNulls, nullables, onParam.toArray(new Annotation[0]));
		if (copiedAnnotationsParam.length != 0) param.annotations = copiedAnnotationsParam;
		
		method.traverse(new SetGeneratedByVisitor(source), parent.scope);
		return method;
	}
	
}
