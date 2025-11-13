package org.drivine.transaction

import org.drivine.DrivineException
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component


@Aspect
@Component
class TransactionalAspect {

    @Autowired
    lateinit var contextHolder: TransactionContextHolder

    @Around("@annotation(org.drivine.transaction.DrivineTransactional) || @annotation(org.springframework.transaction.annotation.Transactional)")
    fun aroundTransactionalMethod(joinPoint: ProceedingJoinPoint): Any? {

        val options = optionsWithDefaults()
        val transaction = contextHolder.currentTransaction ?: Transaction(options, contextHolder)
        val isRoot = transaction.callStack.isEmpty()

        try {
            transaction.pushContext(joinPoint.signature.name)
            val args = joinPoint.args
            val result = joinPoint.proceed(args)
            transaction.popContext(isRoot)
            return result
        } catch (e: Exception) {
            transaction.popContextWithError(e, isRoot)
            throw e
        }
    }

    fun optionsWithDefaults(options: TransactionOptions? = null): TransactionOptions {
        if (options?.propagation != null && options.propagation != Propagation.REQUIRED) {
            throw DrivineException("Only REQUIRED level of propagation is currently supported")
        }

        // Check if we're in a test context and should respect @Rollback
        val testRollback = try {
            // Use reflection to avoid hard dependency on test code
            Class.forName("org.drivine.test.TestTransactionContext")
                .getMethod("shouldRollback")
                .invoke(null) as Boolean?
        } catch (e: Exception) {
            null // Not in test context
        }

        return TransactionOptions(
            rollback = testRollback ?: options?.rollback ?: false,
            propagation = options?.propagation ?: Propagation.REQUIRED,
        )
    }
}

