/*
 * SonarQube Java
 * Copyright (C) 2012 SonarSource
 * sonarqube@googlegroups.com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.java.resolve;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.sonar.java.model.AbstractTypedTree;
import org.sonar.java.model.declaration.VariableTreeImpl;
import org.sonar.plugins.java.api.tree.ClassTree;
import org.sonar.plugins.java.api.tree.MethodTree;
import org.sonar.plugins.java.api.tree.Tree;
import org.sonar.plugins.java.api.tree.TypeParameterTree;
import org.sonar.plugins.java.api.tree.TypeParameters;
import org.sonar.plugins.java.api.tree.TypeTree;
import org.sonar.plugins.java.api.tree.VariableTree;

import java.util.List;
import java.util.Set;

/**
 * Completes hierarchy of types.
 */
public class SecondPass implements JavaSymbol.Completer {

  private final SemanticModel semanticModel;
  private final Symbols symbols;
  private final TypeAndReferenceSolver typeAndReferenceSolver;
  private final ParametrizedTypeCache parametrizedTypeCache;

  public SecondPass(SemanticModel semanticModel, Symbols symbols, ParametrizedTypeCache parametrizedTypeCache, TypeAndReferenceSolver typeAndReferenceSolver) {
    this.semanticModel = semanticModel;
    this.symbols = symbols;
    this.parametrizedTypeCache = parametrizedTypeCache;
    this.typeAndReferenceSolver = typeAndReferenceSolver;
  }

  @Override
  public void complete(JavaSymbol symbol) {
    if (symbol.kind == JavaSymbol.TYP) {
      complete((JavaSymbol.TypeJavaSymbol) symbol);
    } else if (symbol.kind == JavaSymbol.MTH) {
      complete((JavaSymbol.MethodJavaSymbol) symbol);
    } else if (symbol.kind == JavaSymbol.VAR) {
      complete((JavaSymbol.VariableJavaSymbol) symbol);
    } else {
      throw new IllegalArgumentException();
    }
  }

  private void complete(JavaSymbol.TypeJavaSymbol symbol) {
    Resolve.Env env = semanticModel.getEnv(symbol);
    JavaType.ClassJavaType type = (JavaType.ClassJavaType) symbol.type;

    if ((symbol.flags() & Flags.INTERFACE) == 0) {
      // If this is a class, enter symbol for "this"
      symbol.members.enter(new JavaSymbol.VariableJavaSymbol(Flags.FINAL, "this", symbol.type, symbol));
    }

    if ("".equals(symbol.name)) {
      // Anonymous Class Declaration
      // FIXME(Godin): This case avoids NPE which occurs because semanticModel has no associations for anonymous classes.
      type.interfaces = ImmutableList.of();
      return;
    }

    ClassTree tree = symbol.declaration;
    completeTypeParameters(tree.typeParameters(), env);

    populateSuperclass(symbol, env, type);

    if ((symbol.flags() & Flags.INTERFACE) == 0) {
      symbol.members.enter(new JavaSymbol.VariableJavaSymbol(Flags.FINAL, "super", type.supertype, symbol));
    }

    //Interfaces
    ImmutableList.Builder<JavaType> interfaces = ImmutableList.builder();
    for (Tree interfaceTree : tree.superInterfaces()) {
      JavaType interfaceType = resolveType(env, interfaceTree);
      if (interfaceType != null) {
        interfaces.add(interfaceType);
      }
    }

    if (tree.is(Tree.Kind.ANNOTATION_TYPE)) {
      // JLS8 9.6: The direct superinterface of every annotation type is java.lang.annotation.Annotation.
      // (Godin): Note that "extends" and "implements" clauses are forbidden by grammar for annotation types
      interfaces.add(symbols.annotationType);
    }

    if (tree.is(Tree.Kind.ENUM) && symbol.owner.isKind(JavaSymbol.TYP)) {
      // JSL8 8.9: A nested enum type is implicitly static. It is permitted for the declaration of a nested 
      // enum type to redundantly specify the static modifier.
      symbol.flags |= Flags.STATIC;
    }

    type.interfaces = interfaces.build();
  }

  private void populateSuperclass(JavaSymbol.TypeJavaSymbol symbol, Resolve.Env env, JavaType.ClassJavaType type) {
    ClassTree tree = symbol.declaration;
    Tree superClassTree = tree.superClass();
    if (superClassTree != null) {
      type.supertype = resolveType(env, superClassTree);
      checkHierarchyCycles(symbol.type);
    } else if (tree.is(Tree.Kind.ENUM)) {
      // JLS8 8.9: The direct superclass of an enum type E is Enum<E>.
      Scope enumParameters = ((JavaSymbol.TypeJavaSymbol) symbols.enumType.symbol()).typeParameters();
      JavaType.TypeVariableJavaType enumParameter = (JavaType.TypeVariableJavaType) enumParameters.lookup("E").get(0).type();
      type.supertype = parametrizedTypeCache.getParametrizedTypeType(symbols.enumType.symbol, new TypeSubstitution().add(enumParameter, type));
    } else if (tree.is(Tree.Kind.CLASS, Tree.Kind.INTERFACE)) {
      // For CLASS JLS8 8.1.4: the direct superclass of the class type C<F1,...,Fn> is
      // the type given in the extends clause of the declaration of C
      // if an extends clause is present, or Object otherwise.
      // For INTERFACE JLS8 9.1.3: While every class is an extension of class Object, there is no single interface of which all interfaces are
      // extensions.
      // but we can call object method on any interface type.
      type.supertype = symbols.objectType;
    }
  }

  private void completeTypeParameters(TypeParameters typeParameters, Resolve.Env env) {
    for (TypeParameterTree typeParameterTree : typeParameters) {
      List<JavaType> bounds = Lists.newArrayList();
      if(typeParameterTree.bounds().isEmpty()) {
        bounds.add(symbols.objectType);
      } else {
        for (Tree boundTree : typeParameterTree.bounds()) {
          bounds.add(resolveType(env, boundTree));
        }
      }
      ((JavaType.TypeVariableJavaType) semanticModel.getSymbol(typeParameterTree).type()).bounds = bounds;
    }
  }

  private static void checkHierarchyCycles(JavaType baseType) {
    Set<JavaType.ClassJavaType> types = Sets.newHashSet();
    JavaType.ClassJavaType type = (JavaType.ClassJavaType) baseType;
    while (type != null) {
      if (!types.add(type)) {
        throw new IllegalStateException("Cycling class hierarchy detected with symbol : " + baseType.symbol.name + ".");
      }
      type = (JavaType.ClassJavaType) type.symbol.getSuperclass();
    }
  }

  private void complete(JavaSymbol.MethodJavaSymbol symbol) {
    MethodTree methodTree = symbol.declaration;
    Resolve.Env env = semanticModel.getEnv(symbol);
    completeTypeParameters(methodTree.typeParameters(), env);
    ImmutableList.Builder<JavaType> thrownTypes = ImmutableList.builder();
    for (TypeTree throwClause : methodTree.throwsClauses()) {
      JavaType thrownType = resolveType(env, throwClause);
      if (thrownType != null) {
        thrownTypes.add(thrownType);
      }
    }

    JavaType returnType = null;
    // no return type for constructor
    if (!"<init>".equals(symbol.name)) {
      returnType = resolveType(env, methodTree.returnType());
      if (returnType != null) {
        symbol.returnType = returnType.symbol;
      }
    }
    List<VariableTree> parametersTree = methodTree.parameters();
    List<JavaType> argTypes = Lists.newArrayList();
    List<JavaSymbol> scopeSymbols = symbol.parameters.scopeSymbols();
    for(int i = 0; i < parametersTree.size(); i += 1) {
      VariableTree variableTree = parametersTree.get(i);
      JavaSymbol param = scopeSymbols.get(i);
      if (variableTree.simpleName().name().equals(param.getName())) {
        param.complete();
        argTypes.add(param.getType());
      }
      if(((VariableTreeImpl)variableTree).isVararg()) {
        symbol.flags |= Flags.VARARGS;
      }
    }
    JavaType.MethodJavaType methodType = new JavaType.MethodJavaType(argTypes, returnType, thrownTypes.build(), (JavaSymbol.TypeJavaSymbol) symbol.owner);
    symbol.setMethodType(methodType);
  }

  private void complete(JavaSymbol.VariableJavaSymbol symbol) {
    VariableTree variableTree = symbol.declaration;
    Resolve.Env env = semanticModel.getEnv(symbol);
    if (variableTree.is(Tree.Kind.ENUM_CONSTANT)) {
      symbol.type = env.enclosingClass.type;
    } else {
      symbol.type = resolveType(env, variableTree.type());
    }
  }

  private JavaType resolveType(Resolve.Env env, Tree tree) {
    Preconditions.checkArgument(checkTypeOfTree(tree), "Kind of tree unexpected " + tree.kind());
    //FIXME(benzonico) as long as Variables share the same node type, (int i,j; or worse : int i[], j[];) check nullity to respect invariance.
    if (((AbstractTypedTree) tree).isTypeSet()) {
      return (JavaType) ((AbstractTypedTree) tree).symbolType();
    }
    typeAndReferenceSolver.env = env;
    typeAndReferenceSolver.resolveAs(tree, JavaSymbol.TYP, env);
    typeAndReferenceSolver.env = null;
    return (JavaType) ((AbstractTypedTree) tree).symbolType();
  }

  private static boolean checkTypeOfTree(Tree tree) {
    return tree.is(Tree.Kind.MEMBER_SELECT) ||
        tree.is(Tree.Kind.IDENTIFIER) ||
        tree.is(Tree.Kind.PARAMETERIZED_TYPE) ||
        tree.is(Tree.Kind.ARRAY_TYPE) ||
        tree.is(Tree.Kind.UNION_TYPE) ||
        tree.is(Tree.Kind.PRIMITIVE_TYPE) ||
        tree.is(Tree.Kind.INFERED_TYPE);
  }

}
