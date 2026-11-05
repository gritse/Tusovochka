package smithereen.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import smithereen.Config;
import smithereen.Utils;
import smithereen.model.objects.Actor;
import smithereen.model.objects.PropertyValue;
import smithereen.model.groups.GroupBanInfo;
import smithereen.model.groups.GroupBanStatus;
import smithereen.model.groups.GroupFeatureState;
import smithereen.storage.DatabaseUtils;
import smithereen.text.TextProcessor;
import smithereen.util.TranslatableEnum;
import spark.utils.StringUtils;

public final class Group extends Actor{

	public int id;
	public int memberCount, tentativeMemberCount;

	@NotNull
	public Type type=Type.GROUP;

	@Nullable
	public Instant eventStartTime, eventEndTime;

	@NotNull
	public AccessType accessType=AccessType.OPEN;

	@NotNull
	public GroupFeatureState wallState=GroupFeatureState.ENABLED_OPEN;

	@NotNull
	public GroupFeatureState photosState=GroupFeatureState.ENABLED_RESTRICTED;

	@NotNull
	public GroupFeatureState boardState=GroupFeatureState.ENABLED_RESTRICTED;

	@Nullable
	public String website, location;

	@NotNull
	public GroupBanStatus banStatus=GroupBanStatus.NONE;

	@Nullable
	public GroupBanInfo banInfo;

	public static Group fromResultSet(ResultSet res) throws SQLException{
		Group g=new Group();
		g.fillFromResultSet(res);
		return g;
	}

	@Override
	public String getType(){
		return "Group";
	}

	@Override
	public int getLocalID(){
		return id;
	}

	@Override
	public int getOwnerID(){
		return -id;
	}

	@Override
	public String getTypeAndIdForURL(){
		return "/groups/"+id;
	}

	@Override
	public String getName(){
		return name;
	}

	@Override
	public String serializeProfileFields(){
		JsonObject o=new JsonObject();
		if(status!=null)
			o.add("status", Utils.gson.toJsonTree(status));
		o.addProperty("wall", wallState.toString());
		o.addProperty("photos", photosState.toString());
		o.addProperty("board", boardState.toString());
		if(StringUtils.isNotEmpty(website))
			o.addProperty("web", website);
		if(StringUtils.isNotEmpty(location))
			o.addProperty("loc", location);
		return o.toString();
	}

	@Override
	protected void fillFromResultSet(ResultSet res) throws SQLException{
		super.fillFromResultSet(res);
		id=res.getInt("id");
		name=res.getString("name");
		activityPubID=Config.localURI("/groups/"+id);
		url=Config.localURI(username);
		memberCount=res.getInt("member_count");
		tentativeMemberCount=res.getInt("tentative_member_count");
		summary=res.getString("about");
		eventStartTime=DatabaseUtils.getInstant(res, "event_start_time");
		eventEndTime=DatabaseUtils.getInstant(res, "event_end_time");
		type=Type.values()[res.getInt("type")];
		accessType=AccessType.values()[res.getInt("access_type")];

		String fields=res.getString("profile_fields");
		if(fields!=null){
			JsonObject o=JsonParser.parseString(fields).getAsJsonObject();
			if(o.has("status")){
				status=Utils.gson.fromJson(o.get("status"), ActorStatus.class);
			}
			if(o.has("wall"))
				wallState=Utils.enumValue(o.get("wall").getAsString(), GroupFeatureState.class);
			if(o.has("photos"))
				photosState=Utils.enumValue(o.get("photos").getAsString(), GroupFeatureState.class);
			if(o.has("board"))
				boardState=Utils.enumValue(o.get("board").getAsString(), GroupFeatureState.class);
			website=optString(o, "web");
			location=optString(o, "loc");
		}

		if(StringUtils.isNotEmpty(website)){
			if(attachment==null)
				attachment=new ArrayList<>();
			String url=TextProcessor.escapeHTML(website);
			PropertyValue pv=new PropertyValue();
			pv.name="Website";
			pv.value="<a href=\""+url+"\" rel=\"me\">"+url+"</a>";
			pv.parsed=true;
			attachment.add(pv);
		}

		banStatus=GroupBanStatus.values()[res.getInt("ban_status")];
		String _banInfo=res.getString("ban_info");
		if(StringUtils.isNotEmpty(_banInfo)){
			banInfo=Utils.gson.fromJson(_banInfo, GroupBanInfo.class);
		}
	}

	public boolean isEvent(){
		return type==Type.EVENT;
	}

	public enum AdminLevel implements TranslatableEnum<AdminLevel>{
		REGULAR,
		MODERATOR,
		ADMIN,
		OWNER;

		public boolean isAtLeast(AdminLevel lvl){
			return ordinal()>=lvl.ordinal();
		}

		public boolean isAtLeast(String lvl){
			return isAtLeast(valueOf(lvl));
		}

		@Override
		public String getLangKey(){
			return switch(this){
				case REGULAR, OWNER -> "";
				case ADMIN -> "group_access_admin";
				case MODERATOR -> "group_access_moderator";
			};
		}
	}

	public enum MembershipState{
		NONE,
		MEMBER,
		TENTATIVE_MEMBER,
		INVITED,
		REQUESTED,
	}

	public enum Type{
		GROUP,
		EVENT
	}

	public enum AccessType{
		/**
		 * Fully public
		 */
		OPEN,
		/**
		 * Public profile, private content, admins accept new joins
		 */
		CLOSED,
		/**
		 * Doesn't acknowledge its existence unless you're a member, invite-only
		 */
		PRIVATE
	}
}
