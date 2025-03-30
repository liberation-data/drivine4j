package drivine.transaction

import drivine.DrivineException
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

    @Around("@annotation(drivine.transaction.DrivineTransactional)")
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

        return TransactionOptions(
            rollback = options?.rollback ?: false,
            propagation = options?.propagation ?: Propagation.REQUIRED,
        )
    }
}

