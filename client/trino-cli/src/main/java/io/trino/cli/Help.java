/*
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
 */
package io.trino.cli;

public final class Help
{
    private Help() {}

    public static String getHelpText()
    {
        return "" +
                "Supported commands:\n" +
                "QUIT\n" +
                "CLEAR\n" +
                "EXPLAIN [ ( option [, ...] ) ] <query>\n" +
                "    options: FORMAT { TEXT | GRAPHVIZ | JSON }\n" +
                "             TYPE { LOGICAL | DISTRIBUTED | VALIDATE | IO }\n" +
                "DESCRIBE <table>\n" +
                "SHOW COLUMNS FROM <table>\n" +
                "SHOW FUNCTIONS\n" +
                "SHOW CATALOGS [LIKE <pattern>]\n" +
                "SHOW SCHEMAS [FROM <catalog>] [LIKE <pattern>]\n" +
                "SHOW TABLES [FROM <schema>] [LIKE <pattern>]\n" +
                "USE [<catalog>.]<schema>\n" +
                "";
    }
}
