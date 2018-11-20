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

package ru.org.linux.spring.dao;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Nullable;
import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;

@Repository
public class MsgbaseDao {
  /**
   * Запрос тела сообщения и признака bbcode для сообщения
   */
  private static final String QUERY_MESSAGE_TEXT = "SELECT message, markup FROM msgbase WHERE id=?";
  private static final String QUERY_MESSAGE_TEXT_FROM_WIKI =
      "    select jam_topic_version.version_content " +
          "    from jam_topic, jam_topic_version " +
          "    where jam_topic.current_version_id = jam_topic_version.topic_version_id " +
          "    and jam_topic.topic_id = ?";

  private JdbcTemplate jdbcTemplate;
  private NamedParameterJdbcTemplate namedJdbcTemplate;

  private SimpleJdbcInsert insertMsgbase;

  @Autowired
  public void setDataSource(DataSource dataSource) {
    jdbcTemplate = new JdbcTemplate(dataSource);
    namedJdbcTemplate = new NamedParameterJdbcTemplate(dataSource);

    insertMsgbase = new SimpleJdbcInsert(dataSource);
    insertMsgbase.setTableName("msgbase");
    insertMsgbase.usingColumns("id", "message", "markup");
  }

  @Transactional(rollbackFor = Exception.class, propagation = Propagation.MANDATORY)
  public void saveNewMessage(String message, int msgid, @Nullable String markup) {
    insertMsgbase.execute(ImmutableMap.<String, Object>of(
            "id", msgid,
            "message", message,
            "markup", markup)
    );
  }

  public String getMessageTextFromWiki(int topicId) {
    return jdbcTemplate.queryForObject(QUERY_MESSAGE_TEXT_FROM_WIKI, String.class, topicId);
  }

  private MessageText prepareMessageTextFromResultSet(ResultSet resultSet) throws SQLException {
    String text = resultSet.getString("message");
    String markup = resultSet.getString("markup");
    boolean markdown = "MARKDOWN".equals(markup);
    boolean lorcode = !markdown && !"PLAIN".equals(markup);

    return new MessageText(text, lorcode, markdown);
  }

  public MessageText getMessageText(int msgid) {
    return jdbcTemplate.queryForObject(QUERY_MESSAGE_TEXT, (resultSet, i) -> prepareMessageTextFromResultSet(resultSet), msgid);
  }

  public Map<Integer, MessageText> getMessageText(Collection<Integer> msgids) {
    if (msgids.isEmpty()) {
      return ImmutableMap.of();
    }

    final Map<Integer, MessageText> out = Maps.newHashMapWithExpectedSize(msgids.size());

    namedJdbcTemplate.query(
            "SELECT message, markup, id FROM msgbase WHERE id IN (:list)",
            ImmutableMap.of("list", msgids),
            resultSet -> {
              out.put(resultSet.getInt("id"), prepareMessageTextFromResultSet(resultSet));
            });

    return out;
  }

  public void updateMessage(int msgid, String text, @Nullable String formatMode) {
    namedJdbcTemplate.update(
      "UPDATE msgbase SET message=:message, markup=:markup WHERE id=:msgid",
      ImmutableMap.of("message", text, "msgid", msgid, "markup", formatMode)
    );
  }

  public void appendMessage(int msgid, String text) {
    jdbcTemplate.update(
            "UPDATE msgbase SET message=message||? WHERE id=?",
            text,
            msgid
    );
  }
}
