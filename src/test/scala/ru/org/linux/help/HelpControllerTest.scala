/*
 * Copyright 1998-2016 Linux.org.ru
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package ru.org.linux.help

import org.junit.runner.RunWith
import org.junit.{Before, Test}
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.{Bean, Configuration}
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders._
import org.springframework.test.web.servlet.result.MockMvcResultMatchers._
import org.springframework.test.web.servlet.setup.MockMvcBuilders._
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.{EnableWebMvc, PathMatchConfigurer, WebMvcConfigurerAdapter}
import ru.org.linux.util.markdown.MarkdownFormatter

@RunWith(classOf[SpringJUnit4ClassRunner])
@WebAppConfiguration
@ContextConfiguration(classes = Array(classOf[HelpControllerTestConfig]))
class HelpControllerTest extends MVCTest {
  @Test
  def testOk():Unit = {
    mockMvc.perform(get("/help/lorcode.md")).andExpect(status.is(200))
  }

  @Test
  def test404():Unit = {
    mockMvc.perform(get("/help/wrong.md")).andExpect(status.is(404))
  }
}

@Configuration
@EnableWebMvc
class HelpControllerTestConfig extends WebMvcConfigurerAdapter {
  override def configurePathMatch(configurer: PathMatchConfigurer): Unit = {
    configurer.setUseSuffixPatternMatch(false)
  }

  @Bean
  def controller = {
    val markdown: MarkdownFormatter = mock(classOf[MarkdownFormatter])

    when(markdown.renderToHtml(anyString())).thenReturn("ok")

    new HelpController(markdown)
  }
}

trait MVCTest {
  @Autowired
  var wac : WebApplicationContext = null

  var mockMvc : MockMvc = null

  @Before
  def setup():Unit = {
    this.mockMvc = webAppContextSetup(this.wac).build()
  }
}
