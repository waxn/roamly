package com.roamly.data.repository

import com.roamly.data.api.FamilyCircleCreateRequest
import com.roamly.data.api.FamilyCircleDetailResponse
import com.roamly.data.api.FamilyCircleInviteRequest
import com.roamly.data.api.FamilyCircleInviteResponse
import com.roamly.data.api.FamilyCirclesListResponse
import com.roamly.data.api.FamilyCircleRenameRequest
import com.roamly.data.api.FamilyLocationsResponse
import com.roamly.data.api.FamilyPlaceAlertRequest
import com.roamly.data.api.FamilyPlaceAlertResponse
import com.roamly.data.api.FamilyPlaceCreateRequest
import com.roamly.data.api.FamilyPlaceItem
import com.roamly.data.api.FamilyPlacesResponse
import com.roamly.data.api.FamilyPlaceUpdateRequest
import com.roamly.data.api.FamilyPushTokenRequest
import com.roamly.data.api.FamilyPushTokenUnregisterRequest
import com.roamly.data.api.FamilyShareRequest
import com.roamly.data.api.FamilyShareResponse
import com.roamly.data.api.RoamlyApi
import okhttp3.ResponseBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FamilyRepository @Inject constructor(
    private val api: RoamlyApi,
) {
    suspend fun getCircles(): Result<FamilyCirclesListResponse> =
        safeApiCall { api.getFamilyCircles() }

    suspend fun createCircle(name: String): Result<FamilyCircleDetailResponse> =
        safeApiCall { api.createFamilyCircle(FamilyCircleCreateRequest(name)) }

    suspend fun getCircle(circleId: Int): Result<FamilyCircleDetailResponse> =
        safeApiCall { api.getFamilyCircle(circleId) }

    suspend fun renameCircle(circleId: Int, name: String): Result<ResponseBody> =
        safeApiCall { api.renameFamilyCircle(circleId, FamilyCircleRenameRequest(name)) }

    suspend fun deleteCircle(circleId: Int): Result<ResponseBody> =
        safeApiCall { api.deleteFamilyCircle(circleId) }

    suspend fun invite(circleId: Int, rotate: Boolean = false): Result<FamilyCircleInviteResponse> =
        safeApiCall { api.inviteFamilyCircle(circleId, FamilyCircleInviteRequest(rotate)) }

    suspend fun leaveCircle(circleId: Int): Result<ResponseBody> =
        safeApiCall { api.leaveFamilyCircle(circleId) }

    suspend fun removeMember(circleId: Int, userId: Int): Result<ResponseBody> =
        safeApiCall { api.removeFamilyMember(circleId, userId) }

    suspend fun setShareLocation(circleId: Int, shareLocation: Boolean): Result<FamilyShareResponse> =
        safeApiCall { api.setFamilyShareLocation(FamilyShareRequest(circleId, shareLocation)) }

    suspend fun getPlaces(circleId: Int): Result<FamilyPlacesResponse> =
        safeApiCall { api.getFamilyPlaces(circleId) }

    suspend fun createPlace(
        circleId: Int, name: String, lat: Double, lng: Double,
        radiusM: Double = 150.0, notes: String = "",
    ): Result<FamilyPlaceItem> =
        safeApiCall { api.createFamilyPlace(circleId, FamilyPlaceCreateRequest(name, lat, lng, radiusM, notes)) }

    suspend fun updatePlace(placeId: Int, body: FamilyPlaceUpdateRequest): Result<FamilyPlaceItem> =
        safeApiCall { api.updateFamilyPlace(placeId, body) }

    suspend fun deletePlace(placeId: Int): Result<ResponseBody> =
        safeApiCall { api.deleteFamilyPlace(placeId) }

    suspend fun getAlerts(placeId: Int): Result<FamilyPlaceAlertResponse> =
        safeApiCall { api.getFamilyPlaceAlerts(placeId) }

    suspend fun setAlerts(placeId: Int, onEnter: Boolean?, onExit: Boolean?): Result<FamilyPlaceAlertResponse> =
        safeApiCall { api.setFamilyPlaceAlerts(placeId, FamilyPlaceAlertRequest(onEnter, onExit)) }

    suspend fun getLocations(circleId: Int): Result<FamilyLocationsResponse> =
        safeApiCall { api.getFamilyLocations(circleId) }

    suspend fun registerPushToken(token: String, deviceLabel: String): Result<ResponseBody> =
        safeApiCall { api.registerFamilyPushToken(FamilyPushTokenRequest(token, deviceLabel)) }

    suspend fun unregisterPushToken(token: String): Result<ResponseBody> =
        safeApiCall { api.unregisterFamilyPushToken(FamilyPushTokenUnregisterRequest(token)) }
}
