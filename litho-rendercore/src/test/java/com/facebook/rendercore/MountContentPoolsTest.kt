/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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
 */

package com.facebook.rendercore

import android.app.Activity
import android.app.Service
import android.content.Context
import android.view.View
import androidx.lifecycle.LifecycleService
import com.facebook.rendercore.MountContentPools.acquireMountContent
import com.facebook.rendercore.MountContentPools.clear
import com.facebook.rendercore.MountContentPools.onContextDestroyed
import com.facebook.rendercore.MountContentPools.prefillMountContentPool
import com.facebook.rendercore.MountContentPools.recycle
import com.facebook.rendercore.MountContentPools.setMountContentPoolFactory
import java.lang.Thread
import org.assertj.core.api.Java6Assertions
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController

@RunWith(RobolectricTestRunner::class)
class MountContentPoolsTest {

  private val context: Context = RuntimeEnvironment.getApplication()

  private lateinit var activityController: ActivityController<Activity>

  private lateinit var activity: Activity

  private lateinit var serviceController: ServiceController<LifecycleService>

  private lateinit var service: Service

  @Before
  fun setup() {
    clear()
    setMountContentPoolFactory(null)
    activityController = Robolectric.buildActivity(Activity::class.java).create()
    activity = activityController.get()
    serviceController = Robolectric.buildService(LifecycleService::class.java).create()
    service = serviceController.get()
  }

  @After
  fun cleanup() {
    setMountContentPoolFactory(null)
  }

  @Test
  fun testPrefillMountContentPool() {
    val prefillCount = 4
    val testRenderUnit = TestRenderUnit(id = 0, customPoolSize = prefillCount)
    prefillMountContentPool(context, prefillCount, testRenderUnit)
    Java6Assertions.assertThat(testRenderUnit.createdCount).isEqualTo(prefillCount)
    val testRenderUnitToAcquire = TestRenderUnit(0, customPoolSize = prefillCount)
    for (i in 0 until prefillCount) {
      acquireMountContent(context, testRenderUnitToAcquire)
    }
    Java6Assertions.assertThat(testRenderUnitToAcquire.createdCount).isEqualTo(0)
    acquireMountContent(context, testRenderUnitToAcquire)
    Java6Assertions.assertThat(testRenderUnitToAcquire.createdCount).isEqualTo(1)
  }

  @Test
  fun testCannotReleaseToPoolIfPolicyDoesNotAllow() {
    val prefillCount = 2
    val testRenderUnit =
        TestRenderUnit(id = 0, customPoolSize = prefillCount, policy = PoolingPolicy.AcquireOnly)

    // Assert prefill works
    prefillMountContentPool(context, prefillCount, testRenderUnit)
    Java6Assertions.assertThat(testRenderUnit.createdCount).isEqualTo(2)

    // Assert acquiring works by fetching from pool
    val mountContentList =
        (0 until prefillCount).map { acquireMountContent(context, testRenderUnit) }
    Java6Assertions.assertThat(testRenderUnit.createdCount).isEqualTo(prefillCount)

    // Attempt to release into the pool (should not work)
    for (i in 0 until prefillCount) {
      recycle(context, testRenderUnit.contentAllocator, mountContentList[i])
    }

    // Attempt to acquire again
    for (i in 0 until prefillCount) {
      acquireMountContent(context, testRenderUnit.contentAllocator)
    }
    // The number of creation should double because we had to create content again
    Java6Assertions.assertThat(testRenderUnit.createdCount).isEqualTo(prefillCount * 2)
  }

  @Test
  fun testPrefillMountContentPoolWithCustomPool() {
    val prefillCount = 4
    val customPoolSize = 2
    val testRenderUnit = TestRenderUnit(0, customPoolSize)
    prefillMountContentPool(context, prefillCount, testRenderUnit)
    // the prefill count overrides the default pool size of the render unit
    Java6Assertions.assertThat(testRenderUnit.createdCount).isEqualTo(prefillCount)
    val testRenderUnitToAcquire = TestRenderUnit(0, 2)
    for (i in 0 until prefillCount) {
      acquireMountContent(context, testRenderUnitToAcquire)
    }
    // expect no new render units to be created as the pool has been prefilled
    Java6Assertions.assertThat(testRenderUnitToAcquire.createdCount).isEqualTo(0)
  }

  @Test
  fun testReleaseMountContentForDestroyedContextDoesNothing() {
    val testRenderUnit = TestRenderUnit(0)
    val content1 = acquireMountContent(activity, testRenderUnit)
    recycle(activity, testRenderUnit, content1)
    val content2 = acquireMountContent(activity, testRenderUnit)

    // Assert pooling was working before
    Java6Assertions.assertThat(content1).isSameAs(content2)
    recycle(activity, testRenderUnit, content2)

    // Now destroy the activity and assert pooling no longer works. Next acquire should produce
    // difference content.
    onContextDestroyed(activity)
    val content3 = acquireMountContent(activity, testRenderUnit)
    Java6Assertions.assertThat(content3).isNotSameAs(content1)
  }

  @Test
  fun testDestroyingActivityDoesNotAffectPoolingOfOtherContexts() {
    // Destroy activity context
    activityController.destroy()
    onContextDestroyed(activity)
    val testRenderUnit = TestRenderUnit(0)

    // Create content with different context
    val content1 = acquireMountContent(context, testRenderUnit)
    recycle(context, testRenderUnit, content1)
    val content2 = acquireMountContent(context, testRenderUnit)

    // Ensure different context is unaffected by destroying activity context.
    Java6Assertions.assertThat(content1).isSameAs(content2)
  }

  @Test
  fun testDestroyingServiceReleasesThePool() {
    val testRenderUnit = TestRenderUnit(0)

    val content1 = acquireMountContent(service, testRenderUnit)
    recycle(service, testRenderUnit, content1)
    val content2 = acquireMountContent(service, testRenderUnit)

    // Ensure that the content is reused
    Java6Assertions.assertThat(content1).isSameAs(content2)

    // Recycle the content
    recycle(service, testRenderUnit, content2)

    // Destroy the service
    serviceController.destroy()

    val content3 = acquireMountContent(service, testRenderUnit)
    // Ensure that the content acquired after destroying the service is different
    Java6Assertions.assertThat(content3).isNotSameAs(content2)
  }

  @Test
  fun testAcquiringContentOnBgThreadAndDestroyingServiceReleasesThePool() {
    val testRenderUnit = TestRenderUnit(0)

    var content1: Any? = null
    val bgThread = Thread { content1 = acquireMountContent(service, testRenderUnit) }
    bgThread.start()
    bgThread.join()
    Robolectric.flushForegroundThreadScheduler()

    recycle(service, testRenderUnit, checkNotNull(content1))
    val content2 = acquireMountContent(service, testRenderUnit)

    // Ensure that the content is reused
    Java6Assertions.assertThat(content1).isSameAs(content2)

    // Recycle the content
    recycle(service, testRenderUnit, content2)

    // Destroy the service
    serviceController.destroy()

    val content3 = acquireMountContent(service, testRenderUnit)
    // Ensure that the content acquired after destroying the service is different
    Java6Assertions.assertThat(content3).isNotSameAs(content2)
  }

  @Test
  fun testAcquireAndReleaseReturnsCorrectContentInstances() {
    val testRenderUnitToAcquire = TestRenderUnit(id = 0, customPoolSize = 2)

    // acquire content objects
    val firstContent = acquireMountContent(context, testRenderUnitToAcquire)
    val secondContent = acquireMountContent(context, testRenderUnitToAcquire)

    // both of them should be created and they shouldn't be the same instance
    Java6Assertions.assertThat(testRenderUnitToAcquire.createdCount).isEqualTo(2)
    Java6Assertions.assertThat(firstContent).isNotNull
    Java6Assertions.assertThat(secondContent).isNotSameAs(firstContent)

    // Recycle the second content instance
    recycle(context, testRenderUnitToAcquire, secondContent)

    // acquire the third content instance
    val thirdContent = acquireMountContent(context, testRenderUnitToAcquire)

    // it should be the same instance that was just released
    Java6Assertions.assertThat(thirdContent).isSameAs(secondContent)
  }

  @Test
  fun testAcquireContentWhenPoolIsSize0ReturnsNewContentEveryTime() {
    val testRenderUnitToAcquire = TestRenderUnit(id = 0, customPoolSize = 0) // disable Pooling

    // acquire content objects
    val firstContent = acquireMountContent(context, testRenderUnitToAcquire)
    val secondContent = acquireMountContent(context, testRenderUnitToAcquire)

    // both of them should be created and they shouldn't be the same instance
    Java6Assertions.assertThat(testRenderUnitToAcquire.createdCount).isEqualTo(2)
    Java6Assertions.assertThat(firstContent).isNotNull
    Java6Assertions.assertThat(secondContent).isNotSameAs(firstContent)

    // Recycle the second content instance
    recycle(context, testRenderUnitToAcquire, secondContent)

    // acquire the third content instance
    val thirdContent = acquireMountContent(context, testRenderUnitToAcquire)

    // it should not be the same as just released instance because pool size is 0
    Java6Assertions.assertThat(thirdContent).isNotSameAs(secondContent)
  }

  @Test
  fun testAcquireContentWhenPoolingIsDisabledReturnsNewContentEveryTime() {
    val testRenderUnitToAcquire =
        TestRenderUnit(id = 0, customPoolSize = 5, policy = PoolingPolicy.Disabled)

    // acquire content objects
    val firstContent = acquireMountContent(context, testRenderUnitToAcquire)
    val secondContent = acquireMountContent(context, testRenderUnitToAcquire)

    // both of them should be created and they shouldn't be the same instance
    Java6Assertions.assertThat(testRenderUnitToAcquire.createdCount).isEqualTo(2)
    Java6Assertions.assertThat(firstContent).isNotNull
    Java6Assertions.assertThat(secondContent).isNotSameAs(firstContent)

    // Recycle the second content instance
    recycle(context, testRenderUnitToAcquire, secondContent)

    // acquire the third content instance
    val thirdContent = acquireMountContent(context, testRenderUnitToAcquire)

    // it should not be the same as just released instance because pool size is 0
    Java6Assertions.assertThat(thirdContent).isNotSameAs(secondContent)
  }

  @Test
  fun testPolicyIsAcquireOnlyReturnsNewContentEveryTime() {
    val testRenderUnitToAcquire =
        TestRenderUnit(id = 0, customPoolSize = 5, policy = PoolingPolicy.AcquireOnly)

    // acquire content objects
    val firstContent = acquireMountContent(context, testRenderUnitToAcquire)
    val secondContent = acquireMountContent(context, testRenderUnitToAcquire)

    // both of them should be created and they shouldn't be the same instance
    Java6Assertions.assertThat(testRenderUnitToAcquire.createdCount).isEqualTo(2)
    Java6Assertions.assertThat(firstContent).isNotNull
    Java6Assertions.assertThat(secondContent).isNotSameAs(firstContent)

    // Recycle the second content instance
    recycle(context, testRenderUnitToAcquire, secondContent)

    // acquire the third content instance
    val thirdContent = acquireMountContent(context, testRenderUnitToAcquire)

    // it should not be the same as just released instance because pool size is 0
    Java6Assertions.assertThat(thirdContent).isNotSameAs(secondContent)
  }

  @Test
  fun testPrefillMountContentPoolWithCustomPoolScope() {
    val poolScope = PoolScope.ManuallyManaged()
    val prefillCount = 4
    val testRenderUnit = TestRenderUnit(id = 0, customPoolSize = prefillCount)
    prefillMountContentPool(context, prefillCount, testRenderUnit, poolScope)
    Java6Assertions.assertThat(testRenderUnit.createdCount).isEqualTo(prefillCount)
    val testRenderUnitToAcquire = TestRenderUnit(0, customPoolSize = prefillCount)
    for (i in 0 until prefillCount) {
      acquireMountContent(context, testRenderUnitToAcquire, poolScope)
    }
    Java6Assertions.assertThat(testRenderUnitToAcquire.createdCount).isEqualTo(0)
    acquireMountContent(context, testRenderUnitToAcquire)
    Java6Assertions.assertThat(testRenderUnitToAcquire.createdCount).isEqualTo(1)
  }

  @Test
  fun testPrefillMountContentPoolWithCustomPoolAndCustomPoolScope() {
    val poolScope = PoolScope.ManuallyManaged()
    val prefillCount = 4
    val customPoolSize = 2
    val testRenderUnit = TestRenderUnit(0, customPoolSize)
    prefillMountContentPool(context, prefillCount, testRenderUnit, poolScope)
    // the prefill count overrides the default pool size of the render unit
    Java6Assertions.assertThat(testRenderUnit.createdCount).isEqualTo(prefillCount)
    val testRenderUnitToAcquire = TestRenderUnit(0, 2)
    for (i in 0 until prefillCount) {
      acquireMountContent(context, testRenderUnitToAcquire, poolScope)
    }
    // expect no new render units to be created as the pool has been prefilled
    Java6Assertions.assertThat(testRenderUnitToAcquire.createdCount).isEqualTo(0)
  }

  @Test
  fun testReleaseMountContentWithCustomPoolScopeForDestroyedContextDoesNothing() {
    val poolScope = PoolScope.ManuallyManaged()
    val testRenderUnit = TestRenderUnit(0)
    val content1 = acquireMountContent(activity, testRenderUnit, poolScope)
    recycle(activity, testRenderUnit, content1, poolScope)
    val content2 = acquireMountContent(activity, testRenderUnit, poolScope)

    // Assert pooling was working before
    Java6Assertions.assertThat(content1).isSameAs(content2)
    recycle(activity, testRenderUnit, content2, poolScope)

    // Now destroy the activity and assert pooling no longer works. Next acquire should produce
    // difference content.
    onContextDestroyed(activity)
    val content3 = acquireMountContent(activity, testRenderUnit, poolScope)
    Java6Assertions.assertThat(content3).isNotSameAs(content1)
  }

  @Test
  fun testReleaseMountContentWithCustomPoolScopeForReleasedPoolScopeDoesNothing() {
    val poolScope = PoolScope.ManuallyManaged()
    val testRenderUnit = TestRenderUnit(0)
    val content1 = acquireMountContent(activity, testRenderUnit, poolScope)
    recycle(activity, testRenderUnit, content1, poolScope)
    val content2 = acquireMountContent(activity, testRenderUnit, poolScope)

    // Assert pooling was working before
    Java6Assertions.assertThat(content1).isSameAs(content2)
    recycle(activity, testRenderUnit, content2, poolScope)

    // Now release custom pool scope. Next acquire should produce difference content.
    poolScope.releaseScope()
    val content3 = acquireMountContent(activity, testRenderUnit, poolScope)
    Java6Assertions.assertThat(content3).isNotSameAs(content1)
  }

  @Test
  fun testReleaseMountContentWithCustomPoolScopeForReleasedLifecycleAwarePoolScopeDoesNothing() {
    val servicePoolScope = PoolScope.LifecycleAware((service as LifecycleService).lifecycle)
    val testRenderUnit = TestRenderUnit(0)
    val content1 = acquireMountContent(activity, testRenderUnit, servicePoolScope)
    recycle(activity, testRenderUnit, content1, servicePoolScope)
    val content2 = acquireMountContent(activity, testRenderUnit, servicePoolScope)

    // Assert pooling was working before
    Java6Assertions.assertThat(content1).isSameAs(content2)
    recycle(activity, testRenderUnit, content2, servicePoolScope)

    // Now release custom pool scope by destroying the Service. Next acquire should produce
    // difference content.
    serviceController.destroy()
    val content3 = acquireMountContent(activity, testRenderUnit, servicePoolScope)
    Java6Assertions.assertThat(content3).isNotSameAs(content1)
  }

  @Test
  fun testDestroyingActivityReleasesTheCustomScopedPool() {
    val poolScope = PoolScope.ManuallyManaged()
    val testRenderUnit = TestRenderUnit(0)

    val content1 = acquireMountContent(activity, testRenderUnit, poolScope)
    recycle(activity, testRenderUnit, content1, poolScope)
    val content2 = acquireMountContent(activity, testRenderUnit, poolScope)

    // Ensure that the content is reused
    Java6Assertions.assertThat(content1).isSameAs(content2)

    // Recycle the content
    recycle(activity, testRenderUnit, content2, poolScope)

    // Destroy the activity
    activityController.destroy()
    onContextDestroyed(activity)

    val content3 = acquireMountContent(activity, testRenderUnit, poolScope)
    // Ensure that the content acquired after destroying the activity is different
    Java6Assertions.assertThat(content3).isNotSameAs(content2)
  }

  @Test
  fun testAcquireAndReleaseWithDifferentCustomPoolScopesReturnsCorrectContentInstances() {
    val firstPoolScope = PoolScope.ManuallyManaged()
    val secondPoolScope = PoolScope.ManuallyManaged()
    val testRenderUnitToAcquire = TestRenderUnit(id = 0, customPoolSize = 2)

    // acquire content objects
    val firstContentFirstScope =
        acquireMountContent(context, testRenderUnitToAcquire, firstPoolScope)
    val secondContentFirstScope =
        acquireMountContent(context, testRenderUnitToAcquire, firstPoolScope)

    val firstContentSecondScope =
        acquireMountContent(context, testRenderUnitToAcquire, secondPoolScope)
    val secondContentSecondScope =
        acquireMountContent(context, testRenderUnitToAcquire, secondPoolScope)

    // all of them should be created and they shouldn't be the same instance
    Java6Assertions.assertThat(testRenderUnitToAcquire.createdCount).isEqualTo(4)
    Java6Assertions.assertThat(firstContentFirstScope).isNotNull
    Java6Assertions.assertThat(secondContentFirstScope).isNotSameAs(firstContentFirstScope)
    Java6Assertions.assertThat(firstContentSecondScope).isNotNull
    Java6Assertions.assertThat(secondContentSecondScope).isNotSameAs(firstContentSecondScope)

    // Recycle the second content instances
    recycle(context, testRenderUnitToAcquire, secondContentFirstScope, firstPoolScope)
    recycle(context, testRenderUnitToAcquire, secondContentSecondScope, secondPoolScope)

    // acquire the third content instances
    val thirdContentFirstScope =
        acquireMountContent(context, testRenderUnitToAcquire, firstPoolScope)
    val thirdContentSecondScope =
        acquireMountContent(context, testRenderUnitToAcquire, secondPoolScope)

    // they should be the same instances that were just released
    Java6Assertions.assertThat(thirdContentFirstScope).isSameAs(secondContentFirstScope)
    Java6Assertions.assertThat(thirdContentSecondScope).isSameAs(secondContentSecondScope)
  }

  @Test
  fun testAcquireContentWithCustomPoolScopeWhenPoolIsSize0ReturnsNewContentEveryTime() {
    val poolScope = PoolScope.ManuallyManaged()
    val testRenderUnitToAcquire = TestRenderUnit(id = 0, customPoolSize = 0) // disable Pooling

    // acquire content objects
    val firstContent = acquireMountContent(context, testRenderUnitToAcquire, poolScope)
    val secondContent = acquireMountContent(context, testRenderUnitToAcquire, poolScope)

    // both of them should be created and they shouldn't be the same instance
    Java6Assertions.assertThat(testRenderUnitToAcquire.createdCount).isEqualTo(2)
    Java6Assertions.assertThat(firstContent).isNotNull
    Java6Assertions.assertThat(secondContent).isNotSameAs(firstContent)

    // Recycle the second content instance
    recycle(context, testRenderUnitToAcquire, secondContent, poolScope)

    // acquire the third content instance
    val thirdContent = acquireMountContent(context, testRenderUnitToAcquire, poolScope)

    // it should not be the same as just released instance because pool size is 0
    Java6Assertions.assertThat(thirdContent).isNotSameAs(secondContent)
  }

  @Test
  fun testContentDiscardedListenerIsTriggeredWhenPoolIsCleared() {
    val testRenderUnit = TestRenderUnit(0)

    val content1 = acquireMountContent(activity, testRenderUnit)
    recycle(activity, testRenderUnit, content1)

    // Release the pool
    onContextDestroyed(activity)

    // Assert that content discarded was called once
    Java6Assertions.assertThat(testRenderUnit.contentDiscardedCount).isEqualTo(1)
  }

  @Test
  fun testContentDiscardedListenerIsTriggeredWhenScopedPoolIsCleared() {
    val poolScope = PoolScope.ManuallyManaged()
    val testRenderUnit = TestRenderUnit(0)

    val content1 = acquireMountContent(activity, testRenderUnit, poolScope)
    recycle(activity, testRenderUnit, content1, poolScope)

    // Release custom pool scope
    poolScope.releaseScope()

    // Assert that content discarded was called once
    Java6Assertions.assertThat(testRenderUnit.contentDiscardedCount).isEqualTo(1)
  }

  @Test
  fun testContentDiscardedListenerIsTriggeredWhenLifecycleAwareScopedPoolIsCleared() {
    val servicePoolScope = PoolScope.LifecycleAware((service as LifecycleService).lifecycle)
    val testRenderUnit = TestRenderUnit(0)

    val content1 = acquireMountContent(activity, testRenderUnit, servicePoolScope)
    recycle(activity, testRenderUnit, content1, servicePoolScope)

    // Destroy the service should trigger custom pool release
    serviceController.destroy()

    // Assert that content discarded was called once
    Java6Assertions.assertThat(testRenderUnit.contentDiscardedCount).isEqualTo(1)
  }

  @Test
  fun testContentDiscardedListenerIsTriggeredWhenAllPoolsAreCleared() {
    val poolScope = PoolScope.ManuallyManaged()
    val unscopedTestRenderUnit = TestRenderUnit(0)
    val scopedTestRenderUnit = TestRenderUnit(0)

    val unscopedContent = acquireMountContent(activity, unscopedTestRenderUnit)
    val scopedContent = acquireMountContent(activity, scopedTestRenderUnit, poolScope)

    recycle(activity, unscopedTestRenderUnit, unscopedContent)
    recycle(activity, scopedTestRenderUnit, scopedContent, poolScope)

    // release all pools
    clear()

    // Assert that content discarded was called once for both units
    Java6Assertions.assertThat(unscopedTestRenderUnit.contentDiscardedCount).isEqualTo(1)
    Java6Assertions.assertThat(scopedTestRenderUnit.contentDiscardedCount).isEqualTo(1)
  }

  @Test
  fun testContentDiscardedListenerIsTriggeredWhenPoolIsFull() {
    val testRenderUnit = TestRenderUnit(id = 0, customPoolSize = 2)

    val firstContent = acquireMountContent(activity, testRenderUnit)
    val secondContent = acquireMountContent(activity, testRenderUnit)
    val thirdContent = acquireMountContent(activity, testRenderUnit)

    recycle(activity, testRenderUnit, firstContent)
    // Pool size is 2 so first release shouldn't trigger content discarded listener
    Java6Assertions.assertThat(testRenderUnit.contentDiscardedCount).isEqualTo(0)

    recycle(activity, testRenderUnit, secondContent)
    // Pool size is 2 so second release shouldn't trigger content discarded listener
    Java6Assertions.assertThat(testRenderUnit.contentDiscardedCount).isEqualTo(0)

    recycle(activity, testRenderUnit, thirdContent)
    // Pool size is 2 so third release should trigger content discarded listener
    Java6Assertions.assertThat(testRenderUnit.contentDiscardedCount).isEqualTo(1)
  }

  @Test
  fun testContentDiscardedListenerIsTriggeredWhenScopedPoolIsFull() {
    val poolScope = PoolScope.ManuallyManaged()
    val testRenderUnit = TestRenderUnit(id = 0, customPoolSize = 2)

    val firstContent = acquireMountContent(activity, testRenderUnit, poolScope)
    val secondContent = acquireMountContent(activity, testRenderUnit, poolScope)
    val thirdContent = acquireMountContent(activity, testRenderUnit, poolScope)

    recycle(activity, testRenderUnit, firstContent, poolScope)
    // Pool size is 2 so first release shouldn't trigger content discarded listener
    Java6Assertions.assertThat(testRenderUnit.contentDiscardedCount).isEqualTo(0)

    recycle(activity, testRenderUnit, secondContent, poolScope)
    // Pool size is 2 so second release shouldn't trigger content discarded listener
    Java6Assertions.assertThat(testRenderUnit.contentDiscardedCount).isEqualTo(0)

    recycle(activity, testRenderUnit, thirdContent, poolScope)
    // Pool size is 2 so third release should trigger content discarded listener
    Java6Assertions.assertThat(testRenderUnit.contentDiscardedCount).isEqualTo(1)
  }

  class TestRenderUnit(
      override val id: Long,
      private val customPoolSize: Int = ContentAllocator.DEFAULT_MAX_PREALLOCATION,
      private val policy: PoolingPolicy = PoolingPolicy.Default,
  ) : RenderUnit<View>(RenderType.VIEW), ContentAllocator<View> {

    var createdCount: Int = 0
      private set

    var contentDiscardedCount: Int = 0
      private set

    override fun createContent(context: Context): View {
      createdCount++
      return View(context)
    }

    override val contentAllocator: ContentAllocator<View>
      get() = this

    override val poolingPolicy: PoolingPolicy
      get() = policy

    override fun poolSize(): Int = customPoolSize

    override val onContentDiscarded: ((Any) -> Unit)?
      get() = { content -> contentDiscardedCount++ }
  }
}
