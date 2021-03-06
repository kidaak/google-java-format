/*
 * Copyright 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.googlejavaformat;

import com.google.common.base.MoreObjects;
import com.google.common.base.Optional;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.googlejavaformat.Input.Tok;
import com.google.googlejavaformat.Input.Token;
import com.google.googlejavaformat.OpsBuilder.BlankLineWanted;
import com.google.googlejavaformat.Output.BreakTag;

import java.util.ArrayList;
import java.util.List;

/**
 * An {@code OpsBuilder} creates a list of {@link Op}s, which is turned into a {@link Doc} by
 * {@link DocBuilder}.
 */
public final class OpsBuilder {

  /** @return the actual size of the AST node at position, including comments. */
  public int actualSize(int position, int length) {
    Token startToken = input.getPositionTokenMap().floorEntry(position).getValue();
    int start = startToken.getTok().getPosition();
    for (Tok tok : startToken.getToksBefore()) {
      if (tok.isComment()) {
        start = Math.min(start, tok.getPosition());
      }
    }
    Token endToken = input.getPositionTokenMap().lowerEntry(position + length).getValue();
    int end = endToken.getTok().getPosition() + endToken.getTok().getText().length();
    for (Tok tok : endToken.getToksAfter()) {
      if (tok.isComment()) {
        end = Math.max(end, tok.getPosition() + tok.getText().length());
      }
    }
    return end - start;
  }

  /** A request to add or remove a blank line in the output. */
  public abstract static class BlankLineWanted {

    /** Always emit a blank line. */
    public static final BlankLineWanted YES = new SimpleBlankLine(Optional.of(true));

    /** Never emit a blank line. */
    public static final BlankLineWanted NO = new SimpleBlankLine(Optional.of(false));

    /**
     * Explicitly preserve blank lines from the input (e.g. before the first
     * member in a class declaration). Overrides conditional blank lines.
     */
    public static final BlankLineWanted PRESERVE = new SimpleBlankLine(Optional.<Boolean>absent());

    /** Is the blank line wanted? */
    public abstract Optional<Boolean> wanted();

    /** Merge this blank line request with another. */
    public abstract BlankLineWanted merge(BlankLineWanted wanted);

    /** Emit a blank line if the given break is taken. */
    public static BlankLineWanted conditional(BreakTag breakTag) {
      return new ConditionalBlankLine(ImmutableList.of(breakTag));
    }

    private static final class SimpleBlankLine extends BlankLineWanted {
      private final Optional<Boolean> wanted;

      SimpleBlankLine(Optional<Boolean> wanted) {
        this.wanted = wanted;
      }

      @Override
      public Optional<Boolean> wanted() {
        return wanted;
      }

      @Override
      public BlankLineWanted merge(BlankLineWanted other) {
        return this;
      }
    }

    private static final class ConditionalBlankLine extends BlankLineWanted {

      private final ImmutableList<BreakTag> tags;

      ConditionalBlankLine(Iterable<BreakTag> tags) {
        this.tags = ImmutableList.copyOf(tags);
      }

      @Override
      public Optional<Boolean> wanted() {
        for (BreakTag tag : tags) {
          if (tag.wasBreakTaken()) {
            return Optional.of(true);
          }
        }
        return Optional.absent();
      }

      @Override
      public BlankLineWanted merge(BlankLineWanted other) {
        if (!(other instanceof ConditionalBlankLine)) {
          return other;
        }
        return new ConditionalBlankLine(
            Iterables.concat(this.tags, ((ConditionalBlankLine) other).tags));
      }
    }
  }

  private final Input input;
  private final List<Op> ops = new ArrayList<>();
  private final Output output;
  private final List<FormatterDiagnostic> errors;
  private static final Indent.Const ZERO = Indent.Const.ZERO;

  private int tokenI = 0;
  private int inputPosition = Integer.MIN_VALUE;

  /**
   * The {@code OpsBuilder} constructor.
   * @param input the {@link Input}, used for retrieve information from the AST
   * @param output the {@link Output}, used here only to record blank-line information
   * @param errors mutable list to receive errors
   */
  public OpsBuilder(Input input, Output output, List<FormatterDiagnostic> errors) {
    this.input = input;
    this.output = output;
    this.errors = errors; // Assignment of mutable collection.
  }

  /** Get the {@code OpsBuilder}'s {@link Input}. */
  public final Input getInput() {
    return input;
  }

  /**
   * Sync to position in the input. If we've skipped outputting any tokens that were present in the
   * input tokens, output them here and optionally complain.
   * @param inputPosition the {@code 0}-based input position
   */
  public final void sync(int inputPosition) {
    if (inputPosition > this.inputPosition) {
      ImmutableList<? extends Input.Token> tokens = input.getTokens();
      int tokensN = tokens.size();
      if (tokenI < tokensN && inputPosition > tokens.get(tokenI).getTok().getPosition()) {
        // Found a missing input token. Insert it and mark it missing (usually not good).
        Input.Token token = tokens.get(tokenI++);
        throw new AssertionError(
            input
                .createDiagnostic(
                    inputPosition,
                    String.format("did not generate token \"%s\"", token.getTok().getText()))
                .toString());
      }
      this.inputPosition = inputPosition;
    }
  }

  /** Output any remaining tokens from the input stream (e.g. terminal whitespace). */
  public final void drain() {
    int inputPosition = input.getText().length() + 1;
    if (inputPosition > this.inputPosition) {
      ImmutableList<? extends Input.Token> tokens = input.getTokens();
      int tokensN = tokens.size();
      while (tokenI < tokensN && inputPosition > tokens.get(tokenI).getTok().getPosition()) {
        Input.Token token = tokens.get(tokenI++);
        ops.add(
            Doc.Token.make(
                token, Doc.Token.RealOrImaginary.IMAGINARY, ZERO, Optional.<Indent>absent()));
      }
    }
    this.inputPosition = inputPosition;
  }

  /**
   * Open a new level by emitting an {@link OpenOp}.
   * @param plusIndent the extra indent for the new level
   */
  public final void open(Indent plusIndent) {
    open(plusIndent, 0);
  }

  /**
   * Open a new level by emitting an {@link OpenOp}.
   * @param plusIndent the extra indent for the new level
   * @param maxLinesFilled if positive, maximum lines to format in filled mode
   */
  public final void open(Indent plusIndent, int maxLinesFilled) {
    ops.add(OpenOp.make(plusIndent, maxLinesFilled));
  }

  /** Close the current level, by emitting a {@link CloseOp}. */
  public final void close() {
    ops.add(CloseOp.make());
  }

  /** Return the text of the next {@link Input.Token}, or absent if there is none. */
  public final Optional<String> peekToken() {
    ImmutableList<? extends Input.Token> tokens = input.getTokens();
    return tokenI < tokens.size()
        ? Optional.of(tokens.get(tokenI).getTok().getOriginalText())
        : Optional.<String>absent();
  }

  /**
   * Emit an optional token iff it exists on the input. This is used to emit tokens whose existence
   * has been lost in the AST.
   * @param token the optional token
   */
  public final void guessToken(String token) {
    token(token, Doc.Token.RealOrImaginary.IMAGINARY, ZERO, Optional.<Indent>absent());
  }

  public final void token(
      String token,
      Doc.Token.RealOrImaginary realOrImaginary,
      Indent plusIndentCommentsBefore,
      Optional<Indent> breakAndIndentTrailingComment) {
    ImmutableList<? extends Input.Token> tokens = input.getTokens();
    if (token.equals(peekToken().orNull())) { // Found the input token. Output it.
      ops.add(
          Doc.Token.make(
              tokens.get(tokenI++),
              Doc.Token.RealOrImaginary.REAL,
              plusIndentCommentsBefore,
              breakAndIndentTrailingComment));
    } else {
      /*
       * Generated a "bad" token, which doesn't exist on the input. Drop it, and complain unless
       * (for example) we're guessing at an optional token.
       */
      if (realOrImaginary.isReal()) {
        errors.add(
            input.createDiagnostic(
                inputPosition, String.format("generated extra token \"%s\"", token)));
      }
    }
  }

  /**
   * Emit a single- or multi-character op by breaking it into single-character {@link Doc.Token}s.
   * @param op the operator to emit
   */
  public final void op(String op) {
    int opN = op.length();
    for (int i = 0; i < opN; i++) {
      token(
          op.substring(i, i + 1), Doc.Token.RealOrImaginary.REAL, ZERO, Optional.<Indent>absent());
    }
  }

  /** Emit a {@link Doc.Space}. */
  public final void space() {
    ops.add(Doc.Space.make());
  }

  /** Emit a {@link Doc.Break}. */
  public final void breakOp() {
    breakOp(Doc.FillMode.UNIFIED, "", ZERO);
  }

  /**
   * Emit a {@link Doc.Break}.
   * @param plusIndent extra indent if taken
   */
  public final void breakOp(Indent plusIndent) {
    breakOp(Doc.FillMode.UNIFIED, "", plusIndent);
  }

  /** Emit a filled {@link Doc.Break}. */
  public final void breakToFill() {
    breakOp(Doc.FillMode.INDEPENDENT, "", ZERO);
  }

  /** Emit a forced {@link Doc.Break}. */
  public final void forcedBreak() {
    breakOp(Doc.FillMode.FORCED, "", ZERO);
  }

  /**
   * Emit a forced {@link Doc.Break}.
   * @param plusIndent extra indent if taken
   */
  public final void forcedBreak(Indent plusIndent) {
    breakOp(Doc.FillMode.FORCED, "", plusIndent);
  }

  /**
   * Emit a {@link Doc.Break}, with a specified {@code flat} value (e.g., {@code " "}).
   * @param flat the {@link Doc.Break} when not broken
   */
  public final void breakOp(String flat) {
    breakOp(Doc.FillMode.UNIFIED, flat, ZERO);
  }

  /**
   * Emit a {@link Doc.Break}, with a specified {@code flat} value (e.g., {@code " "}).
   * @param flat the {@link Doc.Break} when not broken
   */
  public final void breakToFill(String flat) {
    breakOp(Doc.FillMode.INDEPENDENT, flat, ZERO);
  }

  /**
   * Emit a generic {@link Doc.Break}.
   * @param fillMode the {@link Doc.FillMode}
   * @param flat the {@link Doc.Break} when not broken
   * @param plusIndent extra indent if taken
   */
  public final void breakOp(
      Doc.FillMode fillMode, String flat, Indent plusIndent) {
    breakOp(fillMode, flat, plusIndent, Optional.<BreakTag>absent());
  }

  /**
   * Emit a generic {@link Doc.Break}.
   * @param fillMode the {@link Doc.FillMode}
   * @param flat the {@link Doc.Break} when not broken
   * @param plusIndent extra indent if taken
   * @param optionalTag an optional tag for remembering whether the break was taken
   */
  public final void breakOp(
      Doc.FillMode fillMode, String flat, Indent plusIndent, Optional<BreakTag> optionalTag) {
    ops.add(Doc.Break.make(fillMode, flat, plusIndent, optionalTag));
  }

  /**
   * Make the boundary of a region that can be partially formatted. The
   * boundary will be included in the following region, e.g.:
   * [[boundary0, boundary1), [boundary1, boundary2), ...].
   */
  public void markForPartialFormat() {
    output.markForPartialFormat(getI(input.getTokens().get(tokenI)));
  }

  /**
   * Add a list of {@link Op}s.
   * @param newOps the list of {@link Op}s
   */
  public final void addAll(List<Op> newOps) {
    ops.addAll(newOps);
  }

  /**
   * Force or suppress a blank line here in the output.
   * @param wanted whether to force ({@code true}) or suppress {@code false}) the blank line
   */
  public final void blankLineWanted(BlankLineWanted wanted) {
    output.blankLine(getI(input.getTokens().get(tokenI)), wanted);
  }

  private static int getI(Input.Token token) {
    for (Input.Tok tok : token.getToksBefore()) {
      if (tok.getIndex() >= 0) {
        return tok.getIndex();
      }
    }
    return token.getTok().getIndex();
  }

  private static final Doc.Space SPACE = Doc.Space.make();

  /**
   * Build a list of {@link Op}s from the {@code OpsBuilder}.
   * @return the list of {@link Op}s
   */
  public final ImmutableList<Op> build() {
    // Rewrite the ops to insert comments.
    Multimap<Integer, Op> tokOps = ArrayListMultimap.create();
    int opsN = ops.size();
    for (int i = 0; i < opsN; i++) {
      Op op = ops.get(i);
      if (op instanceof Doc.Token) {
        /*
         * Token ops can have associated non-tokens, including comments, which we need to insert.
         * They can also cause line breaks, so we insert them before or after the current level,
         * when possible.
         */
        Doc.Token tokenOp = (Doc.Token) op;
        Input.Token token = tokenOp.getToken();
        int j = i; // Where to insert toksBefore before.
        while (0 < j && ops.get(j - 1) instanceof OpenOp) {
          --j;
        }
        int k = i; // Where to insert toksAfter after.
        while (k + 1 < opsN && ops.get(k + 1) instanceof CloseOp) {
          ++k;
        }
        if (tokenOp.realOrImaginary().isReal()) {
          /*
           * Regular input token. Copy out toksBefore before token, and toksAfter after it. Insert
           * this token's toksBefore at position j.
           */
          int newlines = 0; // Count of newlines in a row.
          boolean space = false; // Do we need an extra space after a previous "/*" comment?
          boolean lastWasComment = false; // Was the last thing we output a comment?
          boolean allowBlankAfterLastComment = false;
          boolean lastWasJavadoc = false;
          for (Input.Tok tokBefore : token.getToksBefore()) {
            if (tokBefore.isNewline()) {
              newlines++;
            } else if (tokBefore.isComment()) {
              tokOps.put(
                  j,
                  Doc.Break.make(
                      tokBefore.isSlashSlashComment() ? Doc.FillMode.FORCED : Doc.FillMode.UNIFIED,
                      "",
                      tokenOp.getPlusIndentCommentsBefore()));
              tokOps.putAll(j, makeComment(tokBefore));
              space = tokBefore.isSlashStarComment();
              newlines = 0;
              lastWasComment = true;
              lastWasJavadoc = tokBefore.isJavadocComment();
              allowBlankAfterLastComment =
                  tokBefore.isSlashSlashComment()
                      || (tokBefore.isSlashStarComment() && !tokBefore.isJavadocComment());
            }
          }
          if (allowBlankAfterLastComment && newlines > 1) {
            // Force a line break after two newlines in a row following a line or block comment
            output.blankLine(token.getTok().getIndex(), BlankLineWanted.YES);
          }
          if (lastWasJavadoc || (lastWasComment && newlines > 0)) {
            tokOps.put(j, Doc.Break.makeForced());
          } else if (space) {
            tokOps.put(j, SPACE);
          }
          // Now we've seen the Token; output the toksAfter.
          for (Input.Tok tokAfter : token.getToksAfter()) {
            if (tokAfter.isComment()) {
              boolean breakAfter =
                  tokAfter.isSlashStarComment()
                      && tokenOp.breakAndIndentTrailingComment().isPresent();
              if (breakAfter) {
                tokOps.put(
                    k + 1,
                    Doc.Break.make(
                        Doc.FillMode.FORCED, "", tokenOp.breakAndIndentTrailingComment().get()));
              } else {
                tokOps.put(k + 1, SPACE);
              }
              tokOps.putAll(k + 1, makeComment(tokAfter));
              if (breakAfter) {
                tokOps.put(k + 1, Doc.Break.make(Doc.FillMode.FORCED, "", ZERO));
              }
            }
          }
        } else {
          /*
           * This input token was mistakenly not generated for output. As no whitespace or comments
           * were generated (presumably), copy all input non-tokens literally, even spaces and
           * newlines.
           */
          for (Input.Tok tokBefore : token.getToksBefore()) {
            tokOps.put(j, Doc.Tok.make(tokBefore));
          }
          for (Input.Tok tokAfter : token.getToksAfter()) {
            tokOps.put(k + 1, Doc.Tok.make(tokAfter));
          }
        }
      }
    }
    /*
     * Construct new list of ops, splicing in the comments. If a comment is inserted immediately
     * before a space, suppress the space.
     */
    ImmutableList.Builder<Op> newOps = ImmutableList.builder();
    boolean afterForcedBreak = false; // Was the last Op a forced break? If so, suppress spaces.
    for (int i = 0; i < opsN; i++) {
      for (Op op : tokOps.get(i)) {
        if (!(afterForcedBreak && op instanceof Doc.Space)) {
          newOps.add(op);
          afterForcedBreak = isForcedBreak(op);
        }
      }
      Op op = ops.get(i);
      if (afterForcedBreak
          && (op instanceof Doc.Space
              || op instanceof Doc.Break
                  && ((Doc.Break) op).getPlusIndent() == 0
                  && " ".equals(((Doc) op).getFlat()))) {
        continue;
      }
      newOps.add(op);
      if (!(op instanceof OpenOp)) {
        afterForcedBreak = isForcedBreak(op);
      }
    }
    for (Op op : tokOps.get(opsN)) {
      if (!(afterForcedBreak && op instanceof Doc.Space)) {
        newOps.add(op);
        afterForcedBreak = isForcedBreak(op);
      }
    }
    return newOps.build();
  }

  private static boolean isForcedBreak(Op op) {
    return op instanceof Doc.Break && ((Doc.Break) op).isForced();
  }

  private static List<Op> makeComment(Input.Tok comment) {
    return comment.isSlashStarComment()
        ? ImmutableList.<Op>of(Doc.Tok.make(comment))
        : ImmutableList.<Op>of(Doc.Tok.make(comment), Doc.Break.makeForced());
  }

  @Override
  public final String toString() {
    return MoreObjects.toStringHelper(this)
        .add("input", input)
        .add("ops", ops)
        .add("errors", errors)
        .add("output", output)
        .add("tokenI", tokenI)
        .add("inputPosition", inputPosition)
        .toString();
  }
}
