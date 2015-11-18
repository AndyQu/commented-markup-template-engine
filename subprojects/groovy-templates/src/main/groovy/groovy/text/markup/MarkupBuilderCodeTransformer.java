/*
 * Copyright 2003-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package groovy.text.markup;

import org.codehaus.groovy.ast.ClassCodeExpressionTransformer;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.DynamicVariable;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.ArrayExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.control.SourceUnit;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * <p>This AST transformer is responsible for modifying a source template into something
 * which can be compiled as a {@link groovy.text.markup.BaseTemplate} subclass.</p>
 *
 * <p>It performs the following operations:</p>
 *
 * <ul>
 *     <li>replace dynamic variables with <i>getModel().get(dynamicVariable)</i> calls</li>
 *     <li>optionally wrap <i>getModel().get(...)</i> calls into <i>tryEscape</i> calls for automatic escaping</li>
 *     <li>replace <i>include XXX:'...'</i> calls with the appropriate <i>includeXXXX</i> method calls</li>
 *     <li>replace <i>':tagName'()</i> calls into <i>methodMissing('tagName', ...)</i> calls</li>
 * </ul>
 *
 * @author Cedric Champeau
 */
class MarkupBuilderCodeTransformer extends ClassCodeExpressionTransformer {

    private final SourceUnit unit;
    private final boolean autoEscape;

    public MarkupBuilderCodeTransformer(final SourceUnit unit, final boolean autoEscape) {
        this.unit = unit;
        this.autoEscape = autoEscape;
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return unit;
    }

    '''
    总的transform入口
    1. 动态变量 的处理逻辑放在了这个函数中
        model -> this.getModel()
        unescaped -> this.getModel()
        变量名，无需转义    -> this.getModel().get("变量名")
        变量名，有转义     ->  this.tryEscapse(this.getModel().get("变量名"))
    2. 函数
    3. closure
    '''
    @Override
    public Expression transform(final Expression exp) {
        if (exp instanceof MethodCallExpression) {
            return transformMethodCall((MethodCallExpression) exp);
        }
        if (exp instanceof ClosureExpression) {
            ClosureExpression cl = (ClosureExpression) exp;
            cl.getCode().visit(this);
        }
        if (exp instanceof VariableExpression) {
            VariableExpression var = (VariableExpression) exp;
            if (var.getAccessedVariable() instanceof DynamicVariable) {
                MethodCallExpression callGetModel = new MethodCallExpression(
                        new VariableExpression("this"),
                        "getModel",
                        ArgumentListExpression.EMPTY_ARGUMENTS
                );
                '''
                这里设置了VariableExpression("this")作为object expression，为什么还要setImplicitThis(true)？
                意思可能是说，生成代码的时候，就不要产生"this."字符串了
                '''
                callGetModel.setImplicitThis(true);
                callGetModel.setSourcePosition(exp);
                String varName = var.getName();
                if ("model".equals(varName) || "unescaped".equals(varName)) {
                    '''
                    model
                    unescaped
                    以上两种变量，全都默认是model本身的binding
                    '''
                    return callGetModel;
                }

                '''
                有具体变量名称时，用this.getModel().get("变量名")
                '''
                MethodCallExpression mce = new MethodCallExpression(
                        callGetModel,
                        "get",
                        new ArgumentListExpression(new ConstantExpression(varName))
                );
                mce.setSourcePosition(exp);
                mce.setImplicitThis(false);

                '''
                根据配置，决定是否进行自动转义：this.tryEscapse(this.getModel().get("变量名"))
                '''
                MethodCallExpression yield = new MethodCallExpression(
                        new VariableExpression("this"),
                        "tryEscape",
                        new ArgumentListExpression(mce)
                );
                yield.setImplicitThis(true);
                yield.setSourcePosition(exp);
                return autoEscape?yield:mce;
            }
        }
        return super.transform(exp);
    }

    '''
    1. 处理include
    2. 动态函数调用 -> methodMissing()
    '''
    private Expression transformMethodCall(final MethodCallExpression exp) {
        String name = exp.getMethodAsString();
        if (exp.isImplicitThis() && "include".equals(name)) {
            return tryTransformInclude(exp);
        } else if (exp.isImplicitThis() && name.startsWith(":")) {
            '''
            变换为函数动态调用
            '''
            List<Expression> args;
            if (exp.getArguments() instanceof ArgumentListExpression) {
                args = ((ArgumentListExpression) exp.getArguments()).getExpressions();
            } else {
                args = Collections.singletonList(exp.getArguments());
            }
            '''
            new ArgumentListExpression(new ConstantExpression(name.substring(1)), new ArrayExpression(ClassHelper.OBJECT_TYPE, args))
            因为是methodMissing调用，所以new ConstantExpression(name.substring(1))作为第一个参数（动态调用的函数名）
            '''
            Expression newArguments = transform(new ArgumentListExpression(new ConstantExpression(name.substring(1)), new ArrayExpression(ClassHelper.OBJECT_TYPE, args)));
            MethodCallExpression call = new MethodCallExpression(
                    new VariableExpression("this"),
                    "methodMissing",
                    newArguments
            );
            '''
            isSafe()
            is this a safe method call, i.e. if true then if the source object is null then this method call will return null rather than throwing a null pointer exception
            '''
            call.setImplicitThis(true);
            call.setSafe(exp.isSafe());
            call.setSpreadSafe(exp.isSpreadSafe());
            call.setSourcePosition(exp);
            return call;
        }
        return super.transform(exp);
    }

    '''
    include示例：
        html {
            include template: 'includes/header.tpl'
            include template: 'includes/body.tpl'
        }
    ->
        includeGroovy('includes/header.tpl')
        includeGroovy('includes/body.tpl')
    '''
    private Expression tryTransformInclude(final MethodCallExpression exp) {
        Expression arguments = exp.getArguments();
        if (arguments instanceof TupleExpression) {
            List<Expression> expressions = ((TupleExpression) arguments).getExpressions();
            if (expressions.size() == 1 && expressions.get(0) instanceof MapExpression) {
                MapExpression map = (MapExpression) expressions.get(0);
                List<MapEntryExpression> entries = map.getMapEntryExpressions();
                if (entries.size() == 1) {
                    MapEntryExpression mapEntry = entries.get(0);
                    Expression keyExpression = mapEntry.getKeyExpression();
                    try {
                        IncludeType includeType = IncludeType.valueOf(keyExpression.getText().toLowerCase());
                        MethodCallExpression call = new MethodCallExpression(
                                exp.getObjectExpression(),
                                includeType.getMethodName(),
                                new ArgumentListExpression(
                                        mapEntry.getValueExpression()
                                )
                        );
                        call.setImplicitThis(true);
                        call.setSafe(exp.isSafe());
                        call.setSpreadSafe(exp.isSpreadSafe());
                        call.setSourcePosition(exp);
                        return call;
                    } catch (IllegalArgumentException e) {
                        // not a valid import type, do not modify the code
                    }
                }

            }
        }
        return super.transform(exp);
    }
}
