/**
 * (C) Copyright IBM Corp. 2010, 2015
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */

package com.ibm.bi.dml.test.integration.functions.terms;

import org.junit.Test;

import com.ibm.bi.dml.test.integration.AutomatedTestBase;
import com.ibm.bi.dml.test.integration.TestConfiguration;


public class ScalarToMatrixInLoopTest extends AutomatedTestBase 
{

	
	@SuppressWarnings("deprecation")
	@Override
	public void setUp() {
		baseDirectory = SCRIPT_DIR + "functions/terms/";

		availableTestConfigurations.put("ScalarToMatrixInLoop", new TestConfiguration("TestScalarToMatrixInLoop", new String[] {}));
	}

	@Test
	public void testScalarToMatrixInLoop() {
		int rows = 5, cols = 5;

		TestConfiguration config = getTestConfiguration("ScalarToMatrixInLoop");
		config.addVariable("rows", rows);
		config.addVariable("cols", cols);

		loadTestConfiguration(config);

		runTest();
	}
}
