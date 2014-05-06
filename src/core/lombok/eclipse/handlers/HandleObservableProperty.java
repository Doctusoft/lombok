package lombok.eclipse.handlers;

import static lombok.eclipse.Eclipse.*;
import static lombok.eclipse.handlers.EclipseHandlerUtil.*;

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

import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.jdt.internal.compiler.ast.AllocationExpression;
import org.eclipse.jdt.internal.compiler.ast.Annotation;
import org.eclipse.jdt.internal.compiler.ast.Argument;
import org.eclipse.jdt.internal.compiler.ast.Assignment;
import org.eclipse.jdt.internal.compiler.ast.Expression;
import org.eclipse.jdt.internal.compiler.ast.FieldDeclaration;
import org.eclipse.jdt.internal.compiler.ast.MessageSend;
import org.eclipse.jdt.internal.compiler.ast.MethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.NameReference;
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
	
	@Override public void handle(AnnotationValues<ObservableProperty> annotation, Annotation ast, EclipseNode annotationNode) {
		for (EclipseNode fieldNode : annotationNode.upFromAnnotationToFields()) {
			List<Annotation> onMethod = unboxAndRemoveAnnotationParameter(ast, "onMethod", "@Setter(onMethod=", annotationNode);
			List<Annotation> onParam = unboxAndRemoveAnnotationParameter(ast, "onParam", "@Setter(onParam=", annotationNode);
			createSetterForField(AccessLevel.PUBLIC, fieldNode, annotationNode, annotationNode.get(), true, onMethod, onParam);
			new HandleGetter().createGetterForField(AccessLevel.PUBLIC, fieldNode, annotationNode, annotationNode.get(), true, false, onMethod);
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
		FieldDeclaration listenerField = createListenersField((TypeDeclaration) fieldNode.up().get(), fieldNode, setterName, shouldReturnThis, modifier, source, onMethod, onParam);
		injectField(fieldNode.up(), listenerField);
	}
	
	static FieldDeclaration createListenersField(TypeDeclaration parent, EclipseNode fieldNode, String name, boolean shouldReturnThis, int modifier, ASTNode source, List<Annotation> onMethod, List<Annotation> onParam) {
		FieldDeclaration field = (FieldDeclaration) fieldNode.get();
		FieldDeclaration listenerDeclaration = new FieldDeclaration(("$$" + new String(field.name) + "$listeners").toCharArray(), 0,0);
		listenerDeclaration.type = createTypeReference("com.doctusoft.bean.internal.PropertyListeners", source );
		listenerDeclaration.bits |= Eclipse.ECLIPSE_DO_NOT_TOUCH_FLAG;
		AllocationExpression init = new AllocationExpression();
		init.type = createTypeReference("com.doctusoft.bean.internal.PropertyListeners", source );	// create a new typereference instance, it's important
		listenerDeclaration.initialization = init;
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
		MessageSend invoke = new MessageSend();
		invoke.selector = "fireListeners".toCharArray();
		invoke.receiver = new SingleNameReference(("$$" + new String(field.name) + "$listeners").toCharArray(), p);
		invoke.arguments = new Expression[1];
		invoke.arguments[0] = new SingleNameReference(field.name, p);
		statements.add(invoke);
		
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
