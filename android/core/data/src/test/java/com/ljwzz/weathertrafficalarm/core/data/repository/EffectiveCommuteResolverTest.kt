package com.ljwzz.weathertrafficalarm.core.data.repository

import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.ljwzz.weathertrafficalarm.core.data.db.AppDatabase
import com.ljwzz.weathertrafficalarm.core.data.db.entity.AlarmPlanEntity
import com.ljwzz.weathertrafficalarm.core.data.preferences.FavoritePlace
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettings
import com.ljwzz.weathertrafficalarm.core.model.AlarmArmedState
import com.ljwzz.weathertrafficalarm.core.model.AlarmSound
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef
import com.ljwzz.weathertrafficalarm.core.model.RoutePolicy
import com.ljwzz.weathertrafficalarm.core.model.VibrationPattern
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EffectiveCommuteResolverTest {
    private lateinit var db: AppDatabase
    private lateinit var overrides: PlanCommuteOverrideRepository
    private lateinit var resolver: EffectiveCommuteResolver

    private val home = PlaceRef("home", "家", "北京市", 116.397428, 39.90923, "110000", "010")
    private val office = PlaceRef("office", "公司", "北京市", 116.407428, 39.91923, "110000", "010")
    private val school = PlaceRef("school", "学校", "北京市", 116.417428, 39.92923, "110000", "010")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).build()
        overrides = PlanCommuteOverrideRepository(db.planCommuteOverrideDao())
        resolver = EffectiveCommuteResolver(overrides)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun textOnlyFavoritesDoNotProduceAnEffectiveCommute() {
        val settings = LocalSettings(
            favorites = listOf(FavoritePlace("home", "家", "北京市"), FavoritePlace("office", "公司", "北京市")),
            originId = "home",
            destinationId = "office",
        )

        assertNull(resolver.resolveGlobal(settings))
    }

    @Test
    fun planOverrideTakesPrecedenceOverGlobalCommute() = runBlocking {
        db.alarmPlanDao().upsert(plan("plan-1"))
        overrides.save(
            PlanCommuteOverride(
                planId = "plan-1",
                origin = home,
                destination = school,
                commuteMode = CommuteMode.WALKING,
                updatedAt = 100L,
            ),
        )
        val settings = LocalSettings(
            favorites = listOf(
                FavoritePlace("home", "家", "北京市", home),
                FavoritePlace("office", "公司", "北京市", office),
            ),
            originId = "home",
            destinationId = "office",
            commuteMode = CommuteMode.DRIVING,
        )

        val effective = resolver.resolveForPlan("plan-1", settings)

        assertEquals(CommuteSource.PLAN_OVERRIDE, effective?.source)
        assertEquals(school, effective?.destination)
        assertEquals(CommuteMode.WALKING, effective?.commuteMode)
    }

    private fun plan(id: String) = AlarmPlanEntity(
        id = id,
        revision = 0,
        name = "test",
        enabled = false,
        zoneId = "Asia/Shanghai",
        defaultWakeLocalTime = "06:00",
        arrivalLocalTime = "09:00",
        preparationMinutes = 30,
        maxAdvanceMinutes = 60,
        commuteMode = CommuteMode.DRIVING,
        origin = null,
        destination = null,
        waypoints = emptyList(),
        routePolicy = RoutePolicy.DEFAULT,
        weatherRuleVersion = "v1",
        sound = AlarmSound(),
        vibration = VibrationPattern(),
        snoozeMinutes = 10,
        armedState = AlarmArmedState.DISABLED,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
