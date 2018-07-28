package com.ywzlp.webchat.msg.service;

import java.util.List;
import java.util.stream.Collectors;

import org.apache.shiro.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.NearQuery;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import com.mongodb.WriteResult;
import com.ywzlp.webchat.msg.dto.MessageType;
import com.ywzlp.webchat.msg.dto.WebChatMessage;
import com.ywzlp.webchat.msg.dto.WebUserToken;
import com.ywzlp.webchat.msg.entity.CoordinateEntity;
import com.ywzlp.webchat.msg.entity.UserEntity;
import com.ywzlp.webchat.msg.entity.UserMessageEntity;
import com.ywzlp.webchat.msg.entity.UserTokenEntity;
import com.ywzlp.webchat.msg.repository.CoordinateRepository;
import com.ywzlp.webchat.msg.repository.UserMessageRepository;
import com.ywzlp.webchat.msg.repository.UserRepository;
import com.ywzlp.webchat.msg.repository.UserTokenRepository;
import com.ywzlp.webchat.msg.util.TokenGenerator;

@Service
public class UserService {
	
	/**
	 * 12 hours
	 */
	private static final Long expiredMills = 1000 * 60 * 60 * 12L;
	
	private static final Logger logger = LoggerFactory.getLogger(UserService.class);
	
	@Autowired
	private UserRepository userRepository;
	
	@Autowired
	private UserMessageRepository messageRepository;
	
	@Autowired
	private UserTokenRepository userTokenRepository;
	
	@Autowired
	private MongoOperations mongoOperation;
	
	@Autowired
	private CoordinateRepository coordinateRepository;
	
	/**
	 * Save user
	 * @param user
	 * @return
	 */
	public UserEntity saveUser(UserEntity user) {
		return userRepository.save(user);
	}
	
	/**
	 * Update user
	 * @param user
	 * @return
	 */
	public int updateUser(UserEntity user) {
		Update update = new Update();
		update.set("gender", user.getGender());
		update.set("whatUp", user.getWhatUp());
		update.set("realName", user.getRealName());
		update.set("phoneNumber", user.getPhoneNumber());
		update.set("profilePhoto", user.getProfilePhoto());
		Query query = new Query();
		query.addCriteria(Criteria.where("username").is(user.getUsername()));
		return mongoOperation.upsert(query, update, UserEntity.class).getN();
	}
	
	public UserEntity getCurrentUserEntity() {
		return userRepository.findByUsername(getCurrentUsername());
	}
	
	public boolean isExist(String username) {
		return userRepository.countByUsername(username) > 0;
	}
	
	public UserEntity findUserByUsername(String username) {
		return userRepository.findByUsername(username);
	}
	
	public UserTokenEntity findByAccessToken(String accessToken) {
		UserTokenEntity userToken = userTokenRepository.findByAccessToken(accessToken);
		if (userToken == null) {
			return null;
		}
		if (System.currentTimeMillis() > userToken.getExpiredTime()) {
			userTokenRepository.deleteByAccessToken(accessToken);
			return null;
		}
		return userToken;
	}
	
	public UserTokenEntity findUserTokenByUsername(String username) {
		UserTokenEntity userToken = userTokenRepository.findByUsername(username);
		if (userToken == null) {
			return null;
		}
		if (System.currentTimeMillis() > userToken.getExpiredTime()) {
			userTokenRepository.deleteByUsername(username);
			return null;
		}
		return userToken;
	}
	
	public WebUserToken createToken(String username, String password) {
		UserEntity user = userRepository.findByUsernameAndPassword(username, password);
		if (user == null) {
			return null;
		}
		UserTokenEntity userToken = new UserTokenEntity();
		userToken.setAccessToken(TokenGenerator.generateToken());
		userToken.setExpiredTime(System.currentTimeMillis() + expiredMills);
		userToken.setUsername(username);
		userTokenRepository.save(userToken);
		
		WebUserToken webUserToken = new WebUserToken();
		webUserToken.setUsername(username);
		webUserToken.setAccessToken(userToken.getAccessToken());
		webUserToken.setExpiredTime(userToken.getExpiredTime());
		webUserToken.setGender(user.getGender());
		webUserToken.setPhoneNumber(user.getPhoneNumber());
		webUserToken.setRealName(user.getRealName());
		webUserToken.setWhatUp(user.getWhatUp());
		webUserToken.setProfilePhoto(user.getProfilePhoto());
		return webUserToken;
	}
	
	/**
	 * 获取未读消息
	 * @return
	 */
	public List<WebChatMessage> getUnReadMesssages() {
		String to = getCurrentUsername();
		List<UserMessageEntity> messages = messageRepository.findByToAndStatus(to, UserMessageEntity.UN_READ);
		if (CollectionUtils.isEmpty(messages)) {
			return null;
		}
		return messages.stream().map(m -> {
			WebChatMessage message = new WebChatMessage();
			message.setId(m.getMessageId());
			message.setContent(m.getContent());
			message.setFrom(m.getFrom());
			message.setMessageType(MessageType.SMS);
			message.setTo(m.getTo());
			message.setTimestamp(m.getCreateTime());
			return message;
		}).collect(Collectors.toList());
	}
	
	/**
	 * 保存用户消息
	 * @param message
	 * @return
	 */
	public UserMessageEntity saveMessage(WebChatMessage message) {
		UserMessageEntity userMessage = new UserMessageEntity();
		userMessage.setMessageId(message.getId());
		userMessage.setContent(message.getContent());
		userMessage.setFrom(message.getFrom());
		userMessage.setTo(message.getTo());
		userMessage.setCreateTime(message.getTimestamp());
		userMessage.setStatus(UserMessageEntity.UN_READ);
		return messageRepository.save(userMessage);
	}
	
	public static String getCurrentUsername() {
		UserTokenEntity userToken = (UserTokenEntity) SecurityUtils.getSubject().getPrincipal();
		if (userToken == null) {
			return null;
		}
		return userToken.getUsername();
	}

	public int hasRead(WebChatMessage message) {
		Update update = new Update();
		update.set("status", UserMessageEntity.READED);
		Query query = new Query();
		query.addCriteria(Criteria.where("from").is(message.getFrom()));
		query.addCriteria(Criteria.where("to").is(message.getTo()));
		WriteResult writeResult = mongoOperation.updateMulti(query, update, UserMessageEntity.class);
		return writeResult.getN();
	}
	
	/**
	 * 获取附近人
	 * @param location 当前位置
	 * @return
	 */
	public List<CoordinateEntity> getNearbyPeoples(Point location) {
		String username = UserService.getCurrentUsername();
		CoordinateEntity coordinateEntity = coordinateRepository.findByUsername(username);
		if (coordinateEntity == null) {
			coordinateEntity = new CoordinateEntity();
			coordinateEntity.setUsername(username);
			UserEntity user = userRepository.findByUsername(username);
			coordinateEntity.setGender(user.getGender());
			coordinateEntity.setLocation(new GeoJsonPoint(location));
		} else {
			coordinateEntity.setLocation(new GeoJsonPoint(location));
		}
		coordinateRepository.save(coordinateEntity);
		
		logger.info("current location: [{}, {}]", location.getX(), location.getY());
		
		NearQuery query = NearQuery.near(location).maxDistance(new Distance(20, Metrics.KILOMETERS));
		
		GeoResults<CoordinateEntity> geoResults = mongoOperation.geoNear(query, CoordinateEntity.class);
		
		return geoResults.getContent().stream().map(geo -> {
			CoordinateEntity contant = geo.getContent();
			contant.setDistance(geo.getDistance());
			return contant;
		}).filter(content -> {
			return !content.getUsername().equals(username);
		}).collect(Collectors.toList());
		
	}

	/**
	 * 清除位置信息
	 */
	public void clearLocation() {
		String username = UserService.getCurrentUsername();
		coordinateRepository.deleteByUsername(username);
	}

}
