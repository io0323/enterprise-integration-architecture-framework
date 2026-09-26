package io.eia.platform.testsupport

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.time.Duration

class InfraImagesIT :
    FunSpec({
        test("images.env のダイジェスト固定のイメージを Testcontainers で取得して起動できる") {
            val image = InfraImages.get("PROBE_IMAGE")

            GenericContainer(image)
                .withCommand("sh", "-c", "echo eiaf-ready && sleep 60")
                .waitingFor(Wait.forLogMessage(".*eiaf-ready.*\\n", 1).withStartupTimeout(Duration.ofMinutes(2)))
                .use { container ->
                    container.start()

                    container.isRunning shouldBe true
                    container.dockerImageName shouldContain image.versionPart
                    container.execInContainer("echo", "ok").stdout.trim() shouldBe "ok"
                }
        }
    })
