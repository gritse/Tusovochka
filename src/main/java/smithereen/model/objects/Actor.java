package smithereen.model.objects;

import com.google.gson.JsonParser;

import java.sql.ResultSet;
import java.sql.SQLException;

import smithereen.Config;
import smithereen.model.ActorStatus;
import smithereen.model.Group;
import smithereen.model.SizedImage;
import smithereen.model.User;
import smithereen.model.media.MediaFileRef;
import spark.utils.StringUtils;

// Sealed so that a switch over a wall owner covers both kinds without a default branch
public abstract sealed class Actor extends StoredObject permits User, Group{
	public static final int USERNAME_MAX_LENGTH=64;

	public String username;
	public ActorStatus status;

	public String aboutSource;

	/**
	 * The profile picture: a reference to a media file, with the crop rectangles and rotation chosen by the user.
	 * Null when the actor has no avatar.
	 */
	public MediaFileRef avatar;

	public String getProfileURL(String action){
		return "/"+username+"/"+action;
	}

	public String getProfileURL(){
		return "/"+username;
	}

	public String getAbsoluteProfileURL(){
		return Config.localURI(getProfileURL()).toString();
	}

	public boolean hasAvatar(){
		return avatar!=null;
	}

	public float[] getAvatarCropRegion(){
		return avatar!=null ? avatar.cropRegion : null;
	}

	public String getFullUsername(){
		return username;
	}

	public SizedImage getAvatar(){
		return avatar!=null ? avatar.image() : null;
	}

	/**
	 * @return the ID of the photo in the avatars album that is the current profile picture, or 0 if there is none
	 */
	public long getAvatarPhotoID(){
		return avatar!=null ? avatar.photoID : 0;
	}

	public abstract int getLocalID();
	public abstract String getTypeAndIdForURL();
	public abstract String getName();
	public abstract String serializeProfileFields();

	protected void fillFromResultSet(ResultSet res) throws SQLException{
		String _ava=res.getString("avatar");
		if(_ava!=null){
			if(_ava.startsWith("{")){
				try{
					avatar=MediaFileRef.parse(JsonParser.parseString(_ava).getAsJsonObject());
				}catch(Exception ignore){}
			}
		}

		username=res.getString("username");
		aboutSource=res.getString("about_source");
	}

	public String getAboutSource(){
		return StringUtils.isNotEmpty(aboutSource) ? aboutSource : summary;
	}

	public int getOwnerID(){
		throw new UnsupportedOperationException();
	}

	public String getStatusText(){
		return status!=null && !status.isExpired() ? status.text() : null;
	}
}
