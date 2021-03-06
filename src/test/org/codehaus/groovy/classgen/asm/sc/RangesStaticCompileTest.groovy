/*
 * Copyright 2003-2013 the original author or authors.
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
package org.codehaus.groovy.classgen.asm.sc

import groovy.transform.stc.RangesSTCTest

/**
 * Unit tests for static compilation : ranges.
 *
 * @author Cedric Champeau
 */
@Mixin(StaticCompilationTestSupport)
class RangesStaticCompileTest extends RangesSTCTest {

    @Override
    protected void setUp() {
        super.setUp()
        extraSetup()
    }

    // GROOVY-6482
    void testShouldReturnAnEmptyList() {
        assertScript '''
def list = ["1", "2", "3", "4"]
assert list[0..<0] == []
'''
    }

    // GROOVY-6480
    void testShouldNotThrowClassCastExceptionAtRuntime() {
        assertScript '''
def list = ["1", "2", "3", "4"] as String[]
assert list[0..<0] == []
'''
    }

}

