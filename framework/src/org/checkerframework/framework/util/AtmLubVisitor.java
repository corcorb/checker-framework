package org.checkerframework.framework.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import org.checkerframework.framework.type.AnnotatedTypeFactory;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedArrayType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedDeclaredType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedIntersectionType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedNullType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedPrimitiveType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedTypeVariable;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedUnionType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedWildcardType;
import org.checkerframework.framework.type.QualifierHierarchy;
import org.checkerframework.framework.type.visitor.AbstractAtmComboVisitor;
import org.checkerframework.framework.type.visitor.VisitHistory;
import org.checkerframework.javacutil.ErrorReporter;
import org.checkerframework.javacutil.InternalUtils;

/**
 * Helper class to compute the least upper bound of two AnnotatedTypeMirrors.
 *
 * <p>This class should only be used by {@link AnnotatedTypes#leastUpperBound(AnnotatedTypeFactory,
 * AnnotatedTypeMirror, AnnotatedTypeMirror)}.
 */
class AtmLubVisitor extends AbstractAtmComboVisitor<Void, AnnotatedTypeMirror> {

    private final AnnotatedTypeFactory atypeFactory;
    private final QualifierHierarchy qualifierHierarchy;
    private final VisitHistory visitHistory = new VisitHistory();

    AtmLubVisitor(AnnotatedTypeFactory atypeFactory) {
        this.atypeFactory = atypeFactory;
        this.qualifierHierarchy = atypeFactory.getQualifierHierarchy();
    }

    /**
     * Returns an ATM that is the least upper bound of type1 and type2 and whose Java type is
     * lubJavaType. lubJavaType must be a super type or convertible to the Java types of type1 and
     * type2.
     */
    AnnotatedTypeMirror lub(
            AnnotatedTypeMirror type1, AnnotatedTypeMirror type2, TypeMirror lubJavaType) {
        AnnotatedTypeMirror lub = AnnotatedTypeMirror.createType(lubJavaType, atypeFactory, false);

        if (type1.getKind() == TypeKind.NULL) {
            return lubWithNull((AnnotatedNullType) type1, type2, lub);
        }
        if (type2.getKind() == TypeKind.NULL) {
            return lubWithNull((AnnotatedNullType) type2, type1, lub);
        }

        AnnotatedTypeMirror type1AsLub = AnnotatedTypes.asSuper(atypeFactory, type1, lub);
        AnnotatedTypeMirror type2AsLub = AnnotatedTypes.asSuper(atypeFactory, type2, lub);

        visit(type1AsLub, type2AsLub, lub);
        visitHistory.clear();
        return lub;
    }

    private AnnotatedTypeMirror lubWithNull(
            AnnotatedNullType nullType, AnnotatedTypeMirror otherType, AnnotatedTypeMirror lub) {

        AnnotatedTypeMirror otherAsLub = AnnotatedTypes.asSuper(atypeFactory, otherType, lub);
        lub = otherAsLub.deepCopy();

        if (otherAsLub.getKind() != TypeKind.TYPEVAR && otherAsLub.getKind() != TypeKind.WILDCARD) {
            for (AnnotationMirror nullAnno : nullType.getAnnotations()) {
                AnnotationMirror otherAnno = otherAsLub.getAnnotationInHierarchy(nullAnno);
                AnnotationMirror lubAnno = qualifierHierarchy.leastUpperBound(nullAnno, otherAnno);
                lub.replaceAnnotation(lubAnno);
            }
            return lub;
        }

        // LUB(@N null, T), where T's upper bound is @U and T's lower bound is @L
        // if @L <: @U <: @N then LUB(@N null, T) = @N T
        // if @L <: @N <:@U && @N != @L  then LUB(@N null, T) = @U T
        // if @N <: @L <: @U             then LUB(@N null, T) =    T
        Set<AnnotationMirror> lowerBounds =
                AnnotatedTypes.findEffectiveLowerBoundAnnotations(qualifierHierarchy, otherAsLub);
        for (AnnotationMirror lowerBound : lowerBounds) {
            AnnotationMirror nullAnno = nullType.getAnnotationInHierarchy(lowerBound);
            AnnotationMirror upperBound = otherAsLub.getEffectiveAnnotationInHierarchy(lowerBound);
            if (qualifierHierarchy.isSubtype(upperBound, nullAnno)) {
                // @L <: @U <: @N
                lub.replaceAnnotation(nullAnno);
            } else if (qualifierHierarchy.isSubtype(lowerBound, nullAnno)
                    && !qualifierHierarchy.isSubtype(nullAnno, lowerBound)) {
                // @L <: @N <:@U && @N != @L
                lub.replaceAnnotation(upperBound);
            } // else @N <: @L <: @U
        }
        return lub;
    }

    /**
     * Replaces the primary annotations of lub with the lub of the primary annotations of type1 and
     * type2.
     */
    private void lubPrimaryAnnotations(
            AnnotatedTypeMirror type1, AnnotatedTypeMirror type2, AnnotatedTypeMirror lub) {
        Set<? extends AnnotationMirror> lubSet =
                qualifierHierarchy.leastUpperBounds(type1.getAnnotations(), type2.getAnnotations());
        lub.replaceAnnotations(lubSet);
    }

    /** Casts lub to the type of type and issues an error if type and lub are not the same kind. */
    private <T extends AnnotatedTypeMirror> T castLub(T type, AnnotatedTypeMirror lub) {
        if (type.getKind() != lub.getKind()) {
            ErrorReporter.errorAbort(
                    "AtmLubVisitor: unexpected type. Found: %s Required %s",
                    lub.getKind(), type.getKind());
        }
        @SuppressWarnings("unchecked")
        T castedLub = (T) lub;
        return castedLub;
    }

    @Override
    public Void visitNull_Null(
            AnnotatedNullType type1, AnnotatedNullType type2, AnnotatedTypeMirror lub) {
        // Called to issue warning
        castLub(type1, lub);
        lubPrimaryAnnotations(type1, type2, lub);
        return null;
    }

    @Override
    public Void visitArray_Array(
            AnnotatedArrayType type1, AnnotatedArrayType type2, AnnotatedTypeMirror lub) {
        AnnotatedArrayType lubArray = castLub(type1, lub);
        lubPrimaryAnnotations(type1, type2, lubArray);

        visit(type1.getComponentType(), type2.getComponentType(), lubArray.getComponentType());
        return null;
    }

    @Override
    public Void visitDeclared_Declared(
            AnnotatedDeclaredType type1, AnnotatedDeclaredType type2, AnnotatedTypeMirror lub) {
        AnnotatedDeclaredType castedLub = castLub(type1, lub);

        lubPrimaryAnnotations(type1, type2, lub);
        List<AnnotatedTypeMirror> lubTypArgs = new ArrayList<>();
        for (int i = 0; i < type1.getTypeArguments().size(); i++) {
            AnnotatedTypeMirror type1TypeArg = type1.getTypeArguments().get(i);
            AnnotatedTypeMirror type2TypeArg = type2.getTypeArguments().get(i);
            AnnotatedTypeMirror lubTypeArg;
            if (castedLub.wasRaw()) {
                TypeMirror lubTM =
                        InternalUtils.leastUpperBound(
                                atypeFactory.getProcessingEnv(),
                                type1TypeArg.getUnderlyingType(),
                                type2TypeArg.getUnderlyingType());
                lubTypeArg = AnnotatedTypeMirror.createType(lubTM, atypeFactory, false);
                lubTypArgs.add(lubTypeArg);
            } else {
                lubTypeArg = castedLub.getTypeArguments().get(i);
            }
            lubTypeArgument(type1TypeArg, type2TypeArg, lubTypeArg);
        }
        if (lubTypArgs.size() > 0) {
            castedLub.setTypeArguments(lubTypArgs);
        }
        return null;
    }

    private void lubTypeArgument(
            AnnotatedTypeMirror type1, AnnotatedTypeMirror type2, AnnotatedTypeMirror lub) {
        // In lub(), asSuper is called on type1 and type2, but asSuper does not recur into type
        // arguments, so call asSuper on the type arguments so that they have the same underlying
        // type.
        final AnnotatedTypeMirror type1AsLub = AnnotatedTypes.asSuper(atypeFactory, type1, lub);
        final AnnotatedTypeMirror type2AsLub = AnnotatedTypes.asSuper(atypeFactory, type2, lub);

        // If the type argument is a wildcard or captured wildcard, then the lub computation is
        // slightly different.  The primary annotation on the lower bound is the glb of lower
        // bounds of the type types.  This is because the lub of  Gen<@A ? extends @A Object> and
        // Gen<@B ? extends @A Object> is Gen<@B ? extends @A Object>.   If visit(type1AsLub, type2AsLub, lub)
        // was called instead of the below code, then the lub would be Gen<@A ? extends @A Object>.
        // (Note the lub of  Gen<@A ? super @A Object> and Gen<@A ? super @B Object> does not
        // exist, but Gen<@A ? super @B Object> is returned.)
        if (lub.getKind() == TypeKind.WILDCARD) {
            AnnotatedWildcardType type1Wildcard = (AnnotatedWildcardType) type1AsLub;
            AnnotatedWildcardType type2Wildcard = (AnnotatedWildcardType) type2AsLub;
            AnnotatedWildcardType lubWildcard = (AnnotatedWildcardType) lub;
            lubWildcard(
                    type1Wildcard.getSuperBound(),
                    type1Wildcard.getExtendsBound(),
                    type2Wildcard.getSuperBound(),
                    type2Wildcard.getExtendsBound(),
                    lubWildcard.getSuperBound(),
                    lubWildcard.getExtendsBound());
        } else if (lub.getKind() == TypeKind.TYPEVAR
                && InternalUtils.isCaptured((TypeVariable) lub.getUnderlyingType())) {
            AnnotatedTypeVariable type1Wildcard = (AnnotatedTypeVariable) type1AsLub;
            AnnotatedTypeVariable type2Wildcard = (AnnotatedTypeVariable) type2AsLub;
            AnnotatedTypeVariable lubWildcard = (AnnotatedTypeVariable) lub;
            lubWildcard(
                    type1Wildcard.getLowerBound(),
                    type1Wildcard.getUpperBound(),
                    type2Wildcard.getLowerBound(),
                    type2Wildcard.getUpperBound(),
                    lubWildcard.getLowerBound(),
                    lubWildcard.getUpperBound());
        } else {
            visit(type1AsLub, type2AsLub, lub);
        }
    }

    private void lubWildcard(
            AnnotatedTypeMirror type1LowerBound,
            AnnotatedTypeMirror type1UpperBound,
            AnnotatedTypeMirror type2LowerBound,
            AnnotatedTypeMirror type2UpperBound,
            AnnotatedTypeMirror lubLowerBound,
            AnnotatedTypeMirror lubUpperBound) {
        visit(type1UpperBound, type2UpperBound, lubUpperBound);
        visit(type1LowerBound, type2LowerBound, lubLowerBound);

        for (AnnotationMirror top : qualifierHierarchy.getTopAnnotations()) {
            AnnotationMirror glb =
                    qualifierHierarchy.greatestLowerBound(
                            type1LowerBound,
                            type2LowerBound,
                            type1LowerBound.getAnnotationInHierarchy(top),
                            type2LowerBound.getAnnotationInHierarchy(top));
            if (glb != null) {
                lubLowerBound.replaceAnnotation(glb);
            }
        }
    }

    @Override
    public Void visitPrimitive_Primitive(
            AnnotatedPrimitiveType type1, AnnotatedPrimitiveType type2, AnnotatedTypeMirror lub) {
        // Called to issue warning
        castLub(type1, lub);
        lubPrimaryAnnotations(type1, type2, lub);
        return null;
    }

    @Override
    public Void visitTypevar_Typevar(
            AnnotatedTypeVariable type1, AnnotatedTypeVariable type2, AnnotatedTypeMirror lub1) {

        if (visitHistory.contains(type1, type2)) {
            return null;
        }
        visitHistory.add(type1, type2);
        AnnotatedTypeVariable lub = castLub(type1, lub1);
        visit(type1.getUpperBound(), type2.getUpperBound(), lub.getUpperBound());
        visit(type1.getLowerBound(), type2.getLowerBound(), lub.getLowerBound());

        lubPrimaryOnBoundedType(type1, type2, lub);

        return null;
    }

    @Override
    public Void visitWildcard_Wildcard(
            AnnotatedWildcardType type1, AnnotatedWildcardType type2, AnnotatedTypeMirror lub1) {
        AnnotatedWildcardType lub = castLub(type1, lub1);
        visit(type1.getExtendsBound(), type2.getExtendsBound(), lub.getExtendsBound());
        visit(type1.getSuperBound(), type2.getSuperBound(), lub.getSuperBound());
        lubPrimaryOnBoundedType(type1, type2, lub);

        return null;
    }

    private void lubPrimaryOnBoundedType(
            AnnotatedTypeMirror type1, AnnotatedTypeMirror type2, AnnotatedTypeMirror lub) {
        // For each hierarchy, if type1 is not a subtype of type2 and type2 is not a
        // subtype of type1, then the primary annotation on lub must be the effective upper
        // bound of type1 or type2, whichever is higher.
        Set<AnnotationMirror> type1LowerBoundAnnos =
                AnnotatedTypes.findEffectiveLowerBoundAnnotations(qualifierHierarchy, type1);
        Set<AnnotationMirror> type2LowerBoundAnnos =
                AnnotatedTypes.findEffectiveLowerBoundAnnotations(qualifierHierarchy, type2);

        for (AnnotationMirror lower1 : type1LowerBoundAnnos) {
            AnnotationMirror top = qualifierHierarchy.getTopAnnotation(lower1);

            // Can't just call isSubtype because it will return false if bounds have
            // different annotations on component types
            AnnotationMirror lower2 =
                    qualifierHierarchy.findAnnotationInHierarchy(type2LowerBoundAnnos, top);
            AnnotationMirror upper1 = type1.getEffectiveAnnotationInHierarchy(lower1);
            AnnotationMirror upper2 = type2.getEffectiveAnnotationInHierarchy(lower1);

            if (qualifierHierarchy.isSubtype(upper2, upper1)
                    && qualifierHierarchy.isSubtype(upper1, upper2)
                    && qualifierHierarchy.isSubtype(lower1, lower2)
                    && qualifierHierarchy.isSubtype(lower2, lower1)) {
                continue;
            }

            if (!qualifierHierarchy.isSubtype(upper2, lower1)
                    && !qualifierHierarchy.isSubtype(upper1, lower2)) {
                lub.replaceAnnotation(qualifierHierarchy.leastUpperBound(upper1, upper2));
            }
        }
    }

    @Override
    public Void visitIntersection_Intersection(
            AnnotatedIntersectionType type1,
            AnnotatedIntersectionType type2,
            AnnotatedTypeMirror lub) {
        AnnotatedIntersectionType castedLub = castLub(type1, lub);
        lubPrimaryAnnotations(type1, type2, lub);

        for (int i = 0; i < lub.directSuperTypes().size(); i++) {
            AnnotatedDeclaredType lubST = castedLub.directSuperTypes().get(i);
            visit(type1.directSuperTypes().get(i), type2.directSuperTypes().get(i), lubST);
        }

        return null;
    }

    @Override
    public Void visitUnion_Union(
            AnnotatedUnionType type1, AnnotatedUnionType type2, AnnotatedTypeMirror lub) {
        AnnotatedUnionType castedLub = castLub(type1, lub);
        lubPrimaryAnnotations(type1, type2, lub);

        for (int i = 0; i < castedLub.getAlternatives().size(); i++) {
            AnnotatedDeclaredType lubAltern = castedLub.getAlternatives().get(i);
            visit(type1.getAlternatives().get(i), type2.getAlternatives().get(i), lubAltern);
        }
        return null;
    }

    @Override
    protected String defaultErrorMessage(
            AnnotatedTypeMirror type1, AnnotatedTypeMirror type2, AnnotatedTypeMirror lub) {
        return String.format(
                "AtmLubVisitor: Unexpected combination: type1: %s type2: %s.\ntype1: %s"
                        + "\ntype2: %s\nlub: %s",
                type1.getKind(), type2.getKind(), type1, type2, lub);
    }
}
