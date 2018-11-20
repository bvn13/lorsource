/*
 * Copyright 1998-2018 Linux.org.ru
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

package ru.org.linux.topic;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.UriComponentsBuilder;
import ru.org.linux.auth.CaptchaService;
import ru.org.linux.auth.FloodProtector;
import ru.org.linux.auth.IPBlockDao;
import ru.org.linux.auth.IPBlockInfo;
import ru.org.linux.csrf.CSRFNoAuto;
import ru.org.linux.csrf.CSRFProtectionService;
import ru.org.linux.gallery.Image;
import ru.org.linux.gallery.ImageService;
import ru.org.linux.gallery.UploadedImagePreview;
import ru.org.linux.group.Group;
import ru.org.linux.group.GroupDao;
import ru.org.linux.group.GroupPermissionService;
import ru.org.linux.poll.Poll;
import ru.org.linux.poll.PollVariant;
import ru.org.linux.search.SearchQueueSender;
import ru.org.linux.section.Section;
import ru.org.linux.section.SectionService;
import ru.org.linux.site.ScriptErrorException;
import ru.org.linux.site.Template;
import ru.org.linux.tag.TagName;
import ru.org.linux.tag.TagService;
import ru.org.linux.user.*;
import ru.org.linux.util.CommonMessageFormatter;
import ru.org.linux.util.ExceptionBindingErrorProcessor;
import ru.org.linux.util.formatter.ToLorCodeFormatter;
import ru.org.linux.util.formatter.ToLorCodeTexFormatter;
import ru.org.linux.util.markdown.MarkdownFormatter;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.validation.Valid;
import java.beans.PropertyEditorSupport;
import java.io.File;
import java.io.IOException;
import java.util.*;

@Controller
public class AddTopicController {
  private SearchQueueSender searchQueueSender;

  @Autowired
  private CaptchaService captcha;

  private FloodProtector dupeProtector;
  private IPBlockDao ipBlockDao;
  private GroupDao groupDao;
  @Autowired
  private SectionService sectionService;

  @Autowired
  private TagService tagService;

  @Autowired
  private UserService userService;

  @Autowired
  private ProfileDao profileDao;

  @Autowired
  private TopicPrepareService prepareService;

  @Autowired
  private ToLorCodeFormatter toLorCodeFormatter;

  @Autowired
  private ToLorCodeTexFormatter toLorCodeTexFormatter;

  @Autowired
  private GroupPermissionService groupPermissionService;

  @Autowired
  private AddTopicRequestValidator addTopicRequestValidator;

  @Autowired
  private ImageService imageService;

  @Autowired
  private TopicService topicService;

  @Autowired
  private CommonMessageFormatter commonMessageFormatter;

  private static final int MAX_MESSAGE_LENGTH_ANONYMOUS = 8196;
  private static final int MAX_MESSAGE_LENGTH = 32768;

  @Autowired
  public void setSearchQueueSender(SearchQueueSender searchQueueSender) {
    this.searchQueueSender = searchQueueSender;
  }

  @Autowired
  public void setDupeProtector(FloodProtector dupeProtector) {
    this.dupeProtector = dupeProtector;
  }

  @Autowired
  public void setIpBlockDao(IPBlockDao ipBlockDao) {
    this.ipBlockDao = ipBlockDao;
  }

  @Autowired
  public void setGroupDao(GroupDao groupDao) {
    this.groupDao = groupDao;
  }

  @ModelAttribute("ipBlockInfo")
  private IPBlockInfo loadIPBlock(HttpServletRequest request) {
    return ipBlockDao.getBlockInfo(request.getRemoteAddr());
  }

  @RequestMapping(value = "/add.jsp", method = RequestMethod.GET)
  public ModelAndView add(
          @Valid @ModelAttribute("form") AddTopicRequest form,
          HttpServletRequest request
  ) {
    Template tmpl = Template.getTemplate(request);

    if (form.getMode()==null) {
      form.setMode(tmpl.getFormatMode());
    }

    Map<String, Object> params = new HashMap<>(prepareModel(form, tmpl.getCurrentUser()));

    Group group = form.getGroup();

    if (tmpl.isSessionAuthorized() && !groupPermissionService.isTopicPostingAllowed(group, tmpl.getCurrentUser())) {
      ModelAndView errorView = new ModelAndView("errors/good-penguin");
      errorView.addObject("msgHeader", "Недостаточно прав для постинга тем в эту группу");
      errorView.addObject("msgMessage", groupPermissionService.getPostScoreInfo(group));

      return errorView;
    }

    return new ModelAndView("add", params);
  }

  private String processMessage(String msg, String mode) {
    return commonMessageFormatter.processMessage(msg, mode);
  }

  private ImmutableMap<String, Object> prepareModel(AddTopicRequest form, User currentUser) {
    ImmutableMap.Builder<String, Object> params = ImmutableMap.builder();

    Group group = form.getGroup();

    if (group!=null) {
      params.put("group", group);
      params.put("postscoreInfo", groupPermissionService.getPostScoreInfo(group));
      Section section = sectionService.getSection(group.getSectionId());

      params.put("section", section);

      String addInfo = sectionService.getAddInfo(group.getSectionId());

      if (addInfo!=null) {
        params.put("addportal", addInfo);
      }

      params.put("imagepost", groupPermissionService.isImagePostingAllowed(section, currentUser));
    }

    return params.build();
  }

  private User postingUser(Template tmpl, AddTopicRequest form) {
    User user;

    if (!tmpl.isSessionAuthorized()) {
      if (form.getNick() != null) {
        user = form.getNick();
      } else {
        user = userService.getAnonymous();
      }
    } else {
      user = tmpl.getCurrentUser();
    }

    return user;
  }

  @RequestMapping(value="/add.jsp", method=RequestMethod.POST)
  @CSRFNoAuto
  public ModelAndView doAdd(
          HttpServletRequest request,
          @Valid @ModelAttribute("form") AddTopicRequest form,
          BindingResult errors,
          @ModelAttribute("ipBlockInfo") IPBlockInfo ipBlockInfo
  ) throws Exception {
    Template tmpl = Template.getTemplate(request);
    HttpSession session = request.getSession();

    Group group = form.getGroup();

    Map<String, Object> params = new HashMap<>();

    params.putAll(prepareModel(form, tmpl.getCurrentUser()));

    Section section = null;

    if (group!=null) {
      section = sectionService.getSection(group.getSectionId());
    }

    User user = postingUser(tmpl, form);

    user.checkBlocked(errors);

    Profile profile = profileDao.readProfile(user);

    String formatMode = form.getMode() != null ? form.getMode() : profile.getFormatMode();

    IPBlockDao.checkBlockIP(ipBlockInfo, errors, user);

    if (group!=null && !groupPermissionService.isTopicPostingAllowed(group, user)) {
      errors.reject(null, "Недостаточно прав для постинга тем в эту группу");
    }

    String message = processMessage(form.getMsg(), formatMode);

    if (user.isAnonymous()) {
      if (message.length() > MAX_MESSAGE_LENGTH_ANONYMOUS) {
        errors.rejectValue("msg", null, "Слишком большое сообщение");
      }
    } else {
      if (message.length() > MAX_MESSAGE_LENGTH) {
        errors.rejectValue("msg", null, "Слишком большое сообщение");
      }
    }

    UploadedImagePreview imagePreview = null;

    if (section!=null && groupPermissionService.isImagePostingAllowed(section, user)) {
      if (groupPermissionService.isTopicPostingAllowed(group, user)) {
        File image = imageService.processUploadImage(request);

        imagePreview = imageService.processUpload(user, session, image, errors);
      }

      if (section.isImagepost() && imagePreview == null && !errors.hasErrors()) {
        errors.reject(null, "Изображение отсутствует");
      }
    }

    Poll poll = null;
    
    if (section!=null && section.isPollPostAllowed()) {
      poll = preparePollPreview(form);
    }

    Topic previewMsg = null;

    if (group!=null) {
      previewMsg = new Topic(form, user, request.getRemoteAddr());

      Image imageObject = null;

      if (imagePreview!=null) {
        imageObject = new Image(0, 0, "gallery/preview/" + imagePreview.mainFile().getName());
      }

      List<String> tagNames = TagName.parseAndSanitizeTags(form.getTags());

      if (!groupPermissionService.canCreateTag(section, user)) {
        List<String> newTags = tagService.getNewTags(tagNames);

        if (!newTags.isEmpty()) {
          errors.rejectValue("tags", null, "Вы не можете создавать новые теги ("+ TagService.tagsToString(newTags)+")");
        }
      }

      PreparedTopic preparedTopic = prepareService.prepareTopicPreview(
              previewMsg,
              TagService.namesToRefs(tagNames),
              poll,
              request.isSecure(),
              message,
              imageObject,
              formatMode
      );

      params.put("message", preparedTopic);

      TopicMenu topicMenu = prepareService.getTopicMenu(
              preparedTopic,
              tmpl.getCurrentUser(),
              tmpl.getProf(),
              true
      );

      params.put("topicMenu", topicMenu);
    }

    if (!form.isPreviewMode() && !errors.hasErrors()) {
      CSRFProtectionService.checkCSRF(request, errors);
    }

    if (!form.isPreviewMode() && !errors.hasErrors() && !tmpl.isSessionAuthorized()
      || ipBlockInfo.isCaptchaRequired()) {
      captcha.checkCaptcha(request, errors);
    }

    if (!form.isPreviewMode() && !errors.hasErrors()) {
      dupeProtector.checkDuplication(FloodProtector.Action.ADD_TOPIC, request.getRemoteAddr(), user.getScore() >= 100, errors);
    }

    if (!form.isPreviewMode() && !errors.hasErrors() && group != null) {
      return createNewTopic(request, form, session, group, params, section, user, form.getMsg(), imagePreview, previewMsg);
    } else {
      return new ModelAndView("add", params);
    }
  }

  private ModelAndView createNewTopic(HttpServletRequest request, AddTopicRequest form, HttpSession session, Group group, Map<String, Object> params, Section section, User user, String message, UploadedImagePreview scrn, Topic previewMsg) throws Exception {
    session.removeAttribute("image");

    Profile profile = profileDao.readProfile(user);

    int msgid = topicService.addMessage(
            request,
            form,
            message,
            group,
            user,
            scrn,
            previewMsg,
            commonMessageFormatter.getFormatToStoreInDB(profile, form.getMode())
    );

    if (!previewMsg.isDraft())  {
      searchQueueSender.updateMessageOnly(msgid);
    }

    String messageUrl = "view-message.jsp?msgid=" + msgid;

    if (!section.isPremoderated() || previewMsg.isDraft()) {
      return new ModelAndView(new RedirectView(messageUrl));
    }

    params.put("moderated", section.isPremoderated());
    params.put("url", messageUrl);

    return new ModelAndView("add-done-moderated", params);
  }

  private static Poll preparePollPreview(AddTopicRequest form) {
    List<PollVariant> variants = new ArrayList<>(form.getPoll().length);

    for (String item : form.getPoll()) {
      if (!Strings.isNullOrEmpty(item)) {
        variants.add(new PollVariant(0, item));
      }
    }

    return new Poll(0, 0, form.isMultiSelect(), variants);
  }

  @RequestMapping("/add-section.jsp")
  public ModelAndView showForm(
          @RequestParam("section") int sectionId,
          @RequestParam(value="tag", required = false) String tag
  ) throws UserErrorException {
    Map<String, Object> params = new HashMap<>();

    if (tag!=null) {
      TagName.checkTag(tag);
      params.put("tag", tag);
    }

    params.put("sectionId", sectionId);

    Section section = sectionService.getSection(sectionId);

    params.put("section", section);

    params.put("info", sectionService.getAddInfo(section.getId()));

    params.put("groups", groupDao.getGroups(section));

    return new ModelAndView("add-section", params);
  }

  @InitBinder
  public void initBinder(WebDataBinder binder) {
    binder.registerCustomEditor(Group.class, new PropertyEditorSupport() {
      @Override
      public void setAsText(String text) throws IllegalArgumentException {
        setValue(groupDao.getGroup(Integer.parseInt(text)));
      }

      @Override
      public String getAsText() {
        if (getValue()==null) {
          return null;
        } else {
          return Integer.toString(((Group) getValue()).getId());
        }
      }
    });

    binder.registerCustomEditor(User.class, new UserPropertyEditor(userService));
  }

  @InitBinder("form")
  public void requestValidator(WebDataBinder binder) {
    binder.setValidator(addTopicRequestValidator);

    binder.setBindingErrorProcessor(new ExceptionBindingErrorProcessor());
  }

  @ModelAttribute("modes")
  public Map<String, String> getModes() {
    return commonMessageFormatter.getModes();
  }

  public static String getAddUrl(Section section, String tag) {
    UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/add-section.jsp");
    builder.queryParam("section", section.getId());
    builder.queryParam("tag", tag);

    return builder.build().toUriString();
  }

  public static String getAddUrl(Section section) {
    UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/add-section.jsp");
    builder.queryParam("section", section.getId());

    return builder.build().toUriString();
  }
}
