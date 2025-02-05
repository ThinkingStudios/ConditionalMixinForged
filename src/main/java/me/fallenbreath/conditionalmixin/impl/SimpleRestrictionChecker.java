package me.fallenbreath.conditionalmixin.impl;

import com.google.common.collect.Lists;
import me.fallenbreath.conditionalmixin.ConditionalMixinMod;
import me.fallenbreath.conditionalmixin.api.annotation.Condition;
import me.fallenbreath.conditionalmixin.api.annotation.Restriction;
import me.fallenbreath.conditionalmixin.api.mixin.ConditionTester;
import me.fallenbreath.conditionalmixin.api.mixin.RestrictionCheckFailureCallback;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.javafmlmod.FMLModContainer;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.service.MixinService;
import org.spongepowered.asm.util.Annotations;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A restriction checker implementation
 */
public class SimpleRestrictionChecker implements RestrictionChecker {
	private RestrictionCheckFailureCallback failureCallback = null;

	@Override
	public boolean checkRestriction(String mixinClassName) {
		AnnotationNode restriction = getRestrictionAnnotation(mixinClassName);
		if (restriction != null) {
			List<AnnotationNode> enableConditions = Annotations.getValue(restriction, "require", true);
			for (Result result : this.checkConditions(mixinClassName, enableConditions)) {
				if (!result.success) {
					this.onRestrictionCheckFailure(mixinClassName, result.reason);
					return false;
				}
			}
			List<AnnotationNode> disableConditions = Annotations.getValue(restriction, "conflict", true);
			for (Result result : this.checkConditions(mixinClassName, disableConditions)) {
				if (result.success) {
					this.onRestrictionCheckFailure(mixinClassName, result.reason);
					return false;
				}
			}
		}
		return true;
	}

	@Override
	public void setFailureCallback(RestrictionCheckFailureCallback failureCallback) {
		this.failureCallback = failureCallback;
	}

	@Nullable
	private AnnotationNode getRestrictionAnnotation(String className) {
		try {
			ClassNode classNode = MixinService.getService().getBytecodeProvider().getClassNode(className);
			return Annotations.getVisible(classNode, Restriction.class);
		}
		catch (ClassNotFoundException | IOException e) {
			return null;
		}
	}

	private List<Result> checkConditions(String mixinClassName, List<AnnotationNode> conditions) {
		List<Result> results = Lists.newArrayList();
		for (AnnotationNode condition : conditions) {
			Condition.Type type = Annotations.getValue(condition, "type", Condition.Type.class, Condition.Type.MOD);
			switch (type) {
				case MOD:
					String modId = Annotations.getValue(condition, "value");
					Objects.requireNonNull(modId);
					Optional<FMLModContainer> modContainer = ModList.get().getModObjectById(modId);
					if (!modContainer.isPresent()) {
						results.add(new Result(false, String.format("required mod %s not found", modId)));
						continue;
					}

				case MIXIN:
					String requiredMixinClassName = Annotations.getValue(condition, "value");
					if (!this.checkRestriction(requiredMixinClassName)) {
						results.add(new Result(false, String.format("required mixin class %s disabled", requiredMixinClassName)));
						continue;
					}
					results.add(new Result(true, String.format("conflicted mixin class %s found", requiredMixinClassName)));
					break;

				case TESTER:
					Type clazzType = Annotations.getValue(condition, "tester");
					ConditionTester tester;
					try {
						Class<?> clazz = Class.forName(clazzType.getClassName());
						if (clazz.isInterface()) {
							ConditionalMixinMod.LOGGER.error("Tester class {} is a interface, but it should be a class", clazz.getName());
							continue;
						}
						tester = (ConditionTester)clazz.getConstructor().newInstance();
					}
					catch (Exception e) {
						ConditionalMixinMod.LOGGER.error("Failed to instantiate a ConditionTester from class {}: {}", clazzType.getClassName(), e);
						continue;
					}

					boolean testingResult = tester.isSatisfied(mixinClassName);
					results.add(new Result(testingResult, String.format("tester result = %s", testingResult)));
			}
		}
		return results;
	}

	private void onRestrictionCheckFailure(String mixinClassName, String reason) {
		if (this.failureCallback != null) {
			this.failureCallback.callback(mixinClassName, reason);
		}
	}

	private static class Result {
		public final boolean success;
		public final String reason;

		private Result(boolean success, String reason) {
			this.success = success;
			this.reason = reason;
		}
	}
}
