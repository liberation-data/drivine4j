package org.drivine.studio

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** The studio's two endpoints: what there is to load, and a `loadAll` of it. Neither writes. */
@RestController
@RequestMapping("/drivine/studio/api")
class StudioController(private val service: StudioService) {

    private val log = LoggerFactory.getLogger(StudioController::class.java)

    @GetMapping("/catalog")
    fun catalog(): StudioCatalog = service.catalog

    @PostMapping("/query")
    fun query(@RequestBody query: StudioQuery): StudioResult = service.run(query)

    @ExceptionHandler(StudioQueryException::class)
    fun refused(e: StudioQueryException) = problem(HttpStatus.BAD_REQUEST, e.message)

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(e: HttpMessageNotReadableException) =
        problem(HttpStatus.BAD_REQUEST, "The query could not be read: ${e.mostSpecificCause.message}")

    @ExceptionHandler(Exception::class)
    fun failed(e: Exception): ResponseEntity<StudioProblem> {
        log.warn("A studio query failed", e)
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, e.message ?: e.javaClass.simpleName)
    }

    private fun problem(status: HttpStatus, message: String?) =
        ResponseEntity.status(status).body(StudioProblem(message ?: status.reasonPhrase))
}

data class StudioProblem(val error: String)

